package io.knowledgebrain.learning;
import java.util.*;
class GitHubEvidenceFixtures extends EvidenceSessionFixtures {
 GitHubEvidenceStore github;UUID repo,commit;
 void githubInit(){init();github=new GitHubEvidenceStore(ds,clock,roadmaps);repo=ids.next();commit=ids.next();}
 GitHubEvidenceStore.Input githubInput(){return new GitHubEvidenceStore.Input(node,repo,commit,"Private GitHub memo <script>");}
 GitHubReferenceClient.Reference ref(){return ref(commit,"a".repeat(40));}
 GitHubReferenceClient.Reference ref(UUID id,String sha){return new GitHubReferenceClient.Reference(space,actor,repo,id,"a/b",sha,"Pinned GitHub subject",null,"2020-01-01T00:00:00Z","2026-10-01T00:00:00Z","https://github.com/a/b/commit/"+sha,"PRIVATE","PUBLIC",1);}
 GitHubEvidenceStore.Evidence github(){return github.record(space,roadmap,actor,key(),githubInput(),trace,this::ref).evidence();}
 @Override int events(UUID id){return sql.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Integer.class,id);}
}
