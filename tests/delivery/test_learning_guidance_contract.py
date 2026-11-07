import copy
from pathlib import Path
import unittest
import yaml
from test_knowledge_contracts import check_shape

ROOT=Path(__file__).resolve().parents[2]
PATH='/api/spaces/{spaceId}/roadmaps/{roadmapId}/learning-guidance'
ID='0199e000-0000-7000-8000-000000000001'


class LearningGuidanceContractTests(unittest.TestCase):
    def setUp(self):
        self.doc=yaml.safe_load((ROOT/'contracts/openapi/personal-learning-guidance.yaml').read_text(encoding='utf-8'))
        self.schema=self.doc['paths'][PATH]['get']['responses']['200']['content']['application/json']['schema']
        node=dict(nodeId=ID,title='Ready',status='NOT_STARTED',progressVersion=0,unmetRecommendedCount=0,requiredPredecessors=[],recommendedPredecessors=[])
        self.value=dict(roadmapId=ID,spaceId=ID,visibility='PRIVATE',roadmapVersion=2,algorithm='GRAPH_PROGRESS_V1',basis=[dict(nodeId=ID,status='NOT_STARTED',progressVersion=0)],suggestions=[node],waiting=[])

    def test_gateway_forwards_the_one_private_get_declaration(self):
        gateway=yaml.safe_load((ROOT/'contracts/openapi/gateway-guest.yaml').read_text(encoding='utf-8'))
        self.assertEqual({'$ref':'./personal-learning-guidance.yaml#/paths/~1api~1spaces~1{spaceId}~1roadmaps~1{roadmapId}~1learning-guidance'},gateway['paths'][PATH])
        item=self.doc['paths'][PATH]
        self.assertEqual({'get'},set(item)&{'get','head','post','put','delete','patch','options','trace'})
        self.assertNotIn('requestBody',self.doc['paths'][PATH]['get'])
        parameters=item.get('parameters',[])+item['get'].get('parameters',[])
        self.assertEqual({'spaceId','roadmapId'},{p['name'] for p in parameters})
        self.assertTrue(all(p['in']=='path' for p in parameters))

    def test_response_stays_private_and_read_only_with_bounded_input_and_explanations(self):
        check_shape(self.doc,self.schema,self.value)
        for change in [dict(visibility='PUBLIC'),dict(algorithm='AI'),dict(actorId=ID)]:
            with self.assertRaises(AssertionError):check_shape(self.doc,self.schema,dict(self.value,**change))
        missing=copy.deepcopy(self.value);del missing['basis'][0]['progressVersion']
        with self.assertRaises(AssertionError):check_shape(self.doc,self.schema,missing)
        oversized=copy.deepcopy(self.value);oversized['basis']*=101
        with self.assertRaises(AssertionError):check_shape(self.doc,self.schema,oversized)

    def test_complete_and_empty_graphs_remain_valid_reads(self):
        complete=dict(self.value,basis=[dict(nodeId=ID,status='COMPLETED',progressVersion=1)],suggestions=[],waiting=[])
        check_shape(self.doc,self.schema,complete)
        check_shape(self.doc,self.schema,dict(self.value,basis=[],suggestions=[],waiting=[]))
