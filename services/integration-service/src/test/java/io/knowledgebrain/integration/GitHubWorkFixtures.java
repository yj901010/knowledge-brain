package io.knowledgebrain.integration;
import java.time.*;import java.util.*;import org.junit.jupiter.api.BeforeEach;
class GitHubWorkFixtures {
 GitHubStoreIntegrationTest f;GitHubWorkStore work;UUID repo;
 @BeforeEach void prepare(){f=new GitHubStoreIntegrationTest();f.setup();repo=f.connect().repositoryId();work=new GitHubWorkStore(f.ds,Clock.systemUTC(),f.store);}
 GitHubWorkClient.Item item(long n,String kind,String state,String title,String updated){return new GitHubWorkClient.Item(Long.toString(100000+n),n,title,state,"dependabot[bot]",kind.equals("ISSUE")?null:false,"2020-01-01T00:00:00Z",updated,state.equals("CLOSED")?updated:null,f.source.url()+"/"+(kind.equals("ISSUE")?"issues":"pull")+"/"+n);}
 GitHubWorkClient.Batch batch(String kind,GitHubWorkClient.Item...rows){return new GitHubWorkClient.Batch(f.source,kind,List.of(rows),rows.length,rows.length==20);}
 GitHubWorkStore.Synced sync(String kind,String key,long version,GitHubWorkClient.Batch batch){return work.sync(f.space,repo,kind,f.actor,key,version,batch,f.trace);}
 int events(){return f.sql.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid IN (SELECT item_id FROM github_work_item WHERE space_id=?)",Integer.class,f.space);}
}
