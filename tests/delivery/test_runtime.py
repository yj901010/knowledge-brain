"""Enforce one reviewed runtime across every independently built service."""
import hashlib,json,re,sys,tempfile,unittest,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];NS={'m':'http://maven.apache.org/POM/4.0.0'}
sys.path.insert(0,str(ROOT/'scripts/ci'))
import delivery
class RuntimePolicyTests(unittest.TestCase):
    def test_frontend_only_change_cannot_reuse_an_old_source_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'source';artifact=root/'artifact';source.mkdir();(artifact/'graph').mkdir(parents=True)
            (source/'page.tsx').write_text('original UI');(artifact/'graph/index.html').write_text('original compiled UI');(artifact/'graph/csp.txt').write_text("default-src 'none'")
            revision='a'*40;delivery.write(artifact/'manifest.json',dict(sourceRevision=revision,sourceFiles=delivery.frontend_sources(source),files=delivery.files_hashes(artifact/'graph')))
            delivery.check_web_sources(artifact,revision,source)
            (source/'page.tsx').write_text('changed UI')
            with self.assertRaises(ValueError):delivery.check_web_sources(artifact,revision,source)
    def test_all_service_build_paths_inherit_the_reviewed_java25_base(self):
        lock=json.loads((ROOT/'infra/dependencies/java-runtime.json').read_text());parent=ET.parse(ROOT/'pom.xml').getroot();props=parent.find('m:properties',NS)
        self.assertEqual('25',props.find('m:java.version',NS).text);self.assertEqual(lock['image'],props.find('m:brain.runtime.image',NS).text);self.assertRegex(lock['image'],r'^eclipse-temurin:25\.[^@]+@sha256:[0-9a-f]{64}$')
        modules=[node.text for node in parent.findall('m:modules/m:module',NS) if node.text.startswith('services/')];self.assertEqual(13,len(modules))
        for module in modules:
            pom=ET.parse(ROOT/module/'pom.xml').getroot();jib=pom.find("m:profiles/m:profile[m:id='container-image']/m:build/m:plugins/m:plugin[m:artifactId='jib-maven-plugin']",NS)
            self.assertIsNotNone(jib,module);self.assertEqual('${brain.runtime.image}',jib.find('m:configuration/m:from/m:image',NS).text,module);self.assertEqual('10001:10001',jib.find('m:configuration/m:container/m:user',NS).text,module)
    def test_exact_runtime_license_bytes_are_in_the_common_image_payload(self):
        lock=json.loads((ROOT/'infra/dependencies/java-runtime.json').read_text());payload=ROOT/'infra/runtime/legal'
        for name,record in lock['licenseSources'].items():self.assertEqual(record['sha256'],hashlib.sha256((payload/name).read_bytes()).hexdigest(),name)
        self.assertTrue((payload/'redhat-ubi-EULA.pdf').read_bytes().startswith(b'%PDF'))
        parent=ET.parse(ROOT/'pom.xml').getroot();path=parent.find("m:build/m:pluginManagement/m:plugins/m:plugin[m:artifactId='jib-maven-plugin']/m:configuration/m:extraDirectories/m:paths/m:path",NS)
        self.assertEqual('${project.basedir}/../../infra/runtime/legal',path.find('m:from',NS).text);self.assertEqual('/licenses/knowledge-brain',path.find('m:into',NS).text)
