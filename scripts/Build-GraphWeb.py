"""Build pinned Next static export and CSP hashes for same-origin Gateway hosting."""
import base64,hashlib,json,re,shutil,subprocess,sys,time
sys.stdout.reconfigure(encoding="utf-8")
from pathlib import Path
root=Path(__file__).resolve().parents[1];web=root/'apps/graph-web';npm=shutil.which('npm.cmd') or shutil.which('npm')
subprocess.run([sys.executable,str(root/'scripts/Sync-PublicCurationWeb.py')],check=True)
if not npm:raise RuntimeError('Node/npm runtime required')
# Linux tooling avoids repeated Windows EPERM mkdir failures; runtime export is identical.
if __import__('os').name=='nt':
 import runpy
 web=runpy.run_path(str(root/'scripts/Build-GraphWebKubernetes.py'))['export'](root)
else:
 for args in [['ci','--no-audit','--no-fund'],['run','build']]:
  result=subprocess.run([npm]+args,cwd=web)
  if result.returncode:raise RuntimeError('Pinned Graph Web build failed')
html='\n'.join(path.read_text(encoding='utf-8') for path in sorted((web/'out').rglob('*.html')))
hashes=[]
for attrs,body in re.findall(r'<script([^>]*)>(.*?)</script>',html,re.S):
 if 'src=' not in attrs and body:hashes.append("'sha256-"+base64.b64encode(hashlib.sha256(body.encode()).digest()).decode()+"'")
style=[]
for body in re.findall(r'<style[^>]*>(.*?)</style>',html,re.S):style.append("'sha256-"+base64.b64encode(hashlib.sha256(body.encode()).digest()).decode()+"'")
target=root/'services/gateway-service/src/main/resources/static/graph';target.parent.mkdir(parents=True,exist_ok=True)
# Replace only the managed generated tree. Keep a reversible source-artifact backup,
# so old hashed chunks cannot silently accumulate in subsequent Gateway releases.
boundary=root.resolve();stage=target.parent/('.graph-export-'+str(time.time_ns()))
backup=root/'.local/graph-export-backups'/str(time.time_ns())/'graph'
compiled=root/'services/gateway-service/target/classes/static/graph'
compiled_backup=backup.parent/'compiled-graph'
def linked(path):return path.is_symlink() or getattr(path,'is_junction',lambda:False)()
for path in [target,stage,backup,compiled,compiled_backup]:
 if not path.resolve().is_relative_to(boundary) or linked(path):raise RuntimeError('Generated export path escaped repository')
for directory in [target,compiled,web/'out']:
 if directory.exists() and any(linked(p) for p in directory.rglob('*')):raise RuntimeError('Generated export must contain regular files only')
before={p.relative_to(target).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in target.rglob('*') if p.is_file()} if target.exists() else {}
expected={p.relative_to(web/'out').as_posix() for p in (web/'out').rglob('*') if p.is_file()}|{'csp.txt','THIRD-PARTY-NOTICES.txt'}
shutil.copytree(web/'out',stage)
try:
 if target.exists():backup.parent.mkdir(parents=True,exist_ok=False);target.replace(backup)
 stage.replace(target)
except BaseException:
 if not target.exists() and backup.exists():backup.replace(target)
 raise
# Maven's resource copy adds files without removing old hashed chunks. Invalidate
# only its managed compiled export, reversibly; the next resource phase recreates it.
if compiled.exists():
 compiled_backup.parent.mkdir(parents=True,exist_ok=True);compiled.replace(compiled_backup)
(root/'.local').mkdir(exist_ok=True)
(root/'.local/graph-static-install.json').write_text(json.dumps({'backup':str(backup) if backup.exists() else None,'compiledBackup':str(compiled_backup) if compiled_backup.exists() else None,'previousFiles':before,'removedStaleFiles':sorted(set(before)-expected)},indent=2),encoding='utf-8')
(target/'csp.txt').write_text("default-src 'none'; script-src 'self' "+' '.join(hashes)+"; style-src 'self' "+' '.join(style)+"; connect-src 'self'; worker-src 'self'; img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",encoding='utf-8')
records=[]
lock=json.loads((web/'package-lock.json').read_text())
for name,item in lock['packages'].items():
 if name:records.append({'package':name.removeprefix('node_modules/'),'version':item['version'],'license':item.get('license','UNSPECIFIED'),'optional':item.get('optional',False),'developmentOnly':item.get('dev',False)})
(root/'infra/dependencies/graph-web.json').write_text(json.dumps({'runtime':'static Next.js export; browser-only React, same-origin HTTPS Gateway APIs','directLicenses':{'next':'MIT','react':'MIT','react-dom':'MIT','typescript':'Apache-2.0','react-markdown':'MIT','remark-gfm':'MIT'},'optionalNativeImageLibraries':'LGPL libvips optional build tooling is not copied into browser export or Gateway runtime. No Next server/image optimizer is deployed.','packages':records},indent=2),encoding='utf-8')
notice=target/'THIRD-PARTY-NOTICES.txt'
text='Knowledge Brain Graph Web: Next.js, React, react-markdown and remark-gfm MIT; TypeScript Apache-2.0 build tooling.\n'
for name,item in sorted(lock['packages'].items()):
 if not name or item.get('dev') or not (web/name).is_dir():continue
 package=name.removeprefix('node_modules/')
 licenses=[p for p in sorted((web/name).iterdir()) if p.is_file() and re.match(r'^(license|copying|notice)([._-]|$)',p.name,re.I)]
 for path in licenses:text+='\n'+package+' '+item['version']+' / '+path.name+'\n'+path.read_text(encoding='utf-8',errors='replace')
notice.write_text(text,encoding='utf-8');(root/'apps/graph-web/THIRD-PARTY-NOTICES.txt').write_text(text,encoding='utf-8')
print('PASS pinned TypeScript/Next export, publisher license notices and inline-script CSP hashes')
