"""Run the production cache transport from an imported immutable app image in confined Jobs."""
import argparse, base64, hashlib, json, re, subprocess, time
from pathlib import Path
from local_recovery import Cluster, ROOT, now, write_json

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--image', help='Imported candidate; default is the running Brain Query image')
    parser.add_argument('--javac', required=True)
    args=parser.parse_args()
    c=Cluster(ROOT/'.local/kubeconfig.yaml','knowledge-brain'); uid=c.uid()
    image=args.image or c.get('deployment','brain-query-service','brain-apps')['spec']['template']['spec']['containers'][0]['image']
    if not re.fullmatch(r'(?:docker.io/)?knowledge-brain/brain-query-service@sha256:[0-9a-f]{64}',image):
        raise ValueError('Imported immutable Brain Query image required')
    out=ROOT/'.local/valkey-probe';out.mkdir(exist_ok=True)
    source=ROOT/'scripts/probes/ValkeyCacheProbe.java'
    subprocess.run([args.javac,'-encoding','UTF-8','-d',str(out),str(source)],check=True,capture_output=True)
    binary={p.name.replace('$','__'):base64.b64encode(p.read_bytes()).decode() for p in out.glob('ValkeyCacheProbe*.class')}
    items=[dict(key=p.name.replace('$','__'),path=p.name) for p in out.glob('ValkeyCacheProbe*.class')]+[dict(key='wrong-ca.crt',path='wrong-ca.crt')]
    wrong_ca=c.get('secret','brain-kafka-cluster-ca-cert','brain-events')['data']['ca.crt']
    name='public-cache-probe-'+str(time.time_ns())
    cm=dict(apiVersion='v1',kind='ConfigMap',metadata=dict(name=name,namespace='brain-apps'),
            binaryData={**binary,'wrong-ca.crt':wrong_ca})
    c.apply([cm])
    proof=dict(clusterUid=uid,image=image,sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
               classes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in out.glob('ValkeyCacheProbe*.class')},jobs=[],startedAt=now())
    for denied in [False,True]:
        jobname=name+('-denied' if denied else '-allowed')
        labels={'cache-probe':name}
        if not denied:labels['app.kubernetes.io/name']='brain-query-service'
        container=dict(name='probe',image=image,imagePullPolicy='Never',command=['java','-Xmx128m','-cp',
            '/probe:/app/classes:/app/resources:/app/libs/*','ValkeyCacheProbe']+(['network-denied'] if denied else []),
            securityContext=dict(allowPrivilegeEscalation=False,readOnlyRootFilesystem=True,capabilities=dict(drop=['ALL'])),
            resources=dict(requests=dict(cpu='50m',memory='96Mi'),limits=dict(cpu='1',memory='512Mi')),
            # Borrow the cache's workload selector for this confined control, but never
            # enter the live Query Service endpoints while the probe runs.
            readinessProbe=dict(exec={'command':['sh','-c','exit 1']},periodSeconds=1),
            volumeMounts=[dict(name='probe',mountPath='/probe',readOnly=True),dict(name='tmp',mountPath='/tmp')]
              +([] if denied else [dict(name='client',mountPath='/public-cache',readOnly=True)]))
        job=dict(apiVersion='batch/v1',kind='Job',metadata=dict(name=jobname,namespace='brain-apps'),
            spec=dict(backoffLimit=0,activeDeadlineSeconds=120,ttlSecondsAfterFinished=86400,template=dict(
              metadata=dict(labels=labels),spec=dict(automountServiceAccountToken=False,restartPolicy='Never',
                securityContext=dict(runAsNonRoot=True,runAsUser=10001,runAsGroup=10001,fsGroup=10001,seccompProfile=dict(type='RuntimeDefault')),
                containers=[container],volumes=[dict(name='probe',configMap=dict(name=name,items=items)),dict(name='tmp',emptyDir=dict(sizeLimit='64Mi'))]
                  +([] if denied else [dict(name='client',secret=dict(secretName='brain-public-search-cache'))])))))
        policy=dict(apiVersion='networking.k8s.io/v1',kind='NetworkPolicy',metadata=dict(name=jobname,namespace='brain-apps'),
            spec=dict(podSelector=dict(matchLabels={'cache-probe':name}),policyTypes=['Ingress','Egress'],ingress=[],egress=[
              {'to':[{'namespaceSelector':{'matchLabels':{'kubernetes.io/metadata.name':'brain-data'}},'podSelector':{'matchLabels':{'app':'brain-valkey'}}}],
               'ports':[{'protocol':'TCP','port':6379}]},
              {'to':[{'namespaceSelector':{'matchLabels':{'kubernetes.io/metadata.name':'kube-system'}},'podSelector':{'matchLabels':{'k8s-app':'kube-dns'}}}],
               'ports':[{'protocol':'UDP','port':53},{'protocol':'TCP','port':53}]}]))
        assert c.uid()==uid;c.apply([policy,job]);print('JOB='+jobname,flush=True)
        job_uid=c.get('job',jobname,'brain-apps')['metadata']['uid']
        owner=dict(metadata=dict(ownerReferences=[dict(apiVersion='batch/v1',kind='Job',name=jobname,uid=job_uid)]))
        c.run('patch','networkpolicy',jobname,'-n','brain-apps','--type=merge','-p',json.dumps(owner))
        if not denied:c.run('patch','configmap',name,'-n','brain-apps','--type=merge','-p',json.dumps(owner))
        deadline=time.monotonic()+140;observed_not_ready=False
        while time.monotonic()<deadline:
            status=c.get('job',jobname,'brain-apps').get('status',{})
            live=json.loads(c.run('get','pods','-n','brain-apps','-l','job-name='+jobname,'-o','json'))['items']
            for pod in live:
                if pod.get('status',{}).get('phase')=='Running':
                    assert not any(v.get('type')=='Ready' and v.get('status')=='True' for v in pod['status'].get('conditions',[]))
                    observed_not_ready=True
                    slices=json.loads(c.run('get','endpointslices','-n','brain-apps','-l','kubernetes.io/service-name=brain-query-service','-o','json'))['items']
                    assert not any(e.get('targetRef',{}).get('name')==pod['metadata']['name'] and e.get('conditions',{}).get('ready') for s in slices for e in s.get('endpoints',[]))
            if status.get('failed'):raise RuntimeError(c.run('logs','job/'+jobname,'-n','brain-apps').decode('utf-8')[-3000:])
            if status.get('succeeded'):break
            time.sleep(2)
        else:raise TimeoutError('Cache probe deadline exceeded')
        logs=c.run('logs','job/'+jobname,'-n','brain-apps').decode('utf-8')
        assert 'PASS ' in logs
        assert c.get('deployment','brain-valkey','brain-data')['status']['readyReplicas']==1
        pods=c.get('pods',namespace='brain-apps')['items']
        pod=next(p for p in pods if p['metadata'].get('labels',{}).get('job-name')==jobname)
        image_id=pod['status']['containerStatuses'][0]['imageID'];assert image_id.endswith(image.split('@')[1])
        if not denied:assert observed_not_ready
        proof['jobs'].append(dict(name=jobname,uid=job_uid,imageId=image_id,notReadyWhileRunning=observed_not_ready,log=logs));print(logs,flush=True)
    proof.update(completedAt=now(),state='PASSED');write_json(ROOT/'.local/valkey-transport-proof.json',proof)

if __name__=='__main__':main()
