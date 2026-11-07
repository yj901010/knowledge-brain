package io.knowledgebrain.brainquery;

import com.google.protobuf.InvalidProtocolBufferException;
import io.knowledgebrain.contracts.content.links.v1.LinkEventEnvelopeV1;
import io.knowledgebrain.contracts.content.links.v1.NoteLinksChangedPayloadV1;
import java.security.MessageDigest;
import java.util.*;

/** Private derived graph input. Link ordering is independent from note revision ordering. */
public final class NoteGraphProjection {
    private NoteGraphProjection() {}
    public record Link(UUID linkId, UUID targetNoteId, int ordinal) {}
    public record Links(UUID eventId, UUID spaceId, UUID sourceNoteId, long noteVersion,
                        long linkVersion, String state, List<Link> resolved, String payloadHash) {
        public Links { resolved=List.copyOf(resolved); }
    }
    public static Links decode(byte[] bytes) {
        if(bytes==null || bytes.length>524288) throw invalid();
        try {
            var event=LinkEventEnvelopeV1.parseFrom(bytes);
            if(!event.getProducer().equals("content-service") || !event.getAggregateType().equals("NoteLinks")
                    || !event.getEventType().equals("NoteLinksChanged") || event.getEventVersion()!=1) throw invalid();
            var payload=NoteLinksChangedPayloadV1.parseFrom(event.getPayload());
            var eventId=id(event.getEventId()); var spaceId=id(event.getSpaceId());
            var source=id(payload.getSourceNoteId()); id(payload.getRevisionId());
            if(!source.equals(id(event.getAggregateId())) || payload.getNoteVersion()<=0 || payload.getLinkVersion()<=0
                    || !Set.of("ACTIVE","RETIRED").contains(payload.getSourceState()) || payload.getLinksCount()>256
                    || payload.getSourceState().equals("RETIRED") && payload.getLinksCount()!=0) throw invalid();
            var seen=new HashSet<UUID>(); var resolved=new ArrayList<Link>(); int ordinal=0;
            for(var link:payload.getLinksList()) {
                var linkId=id(link.getLinkId());
                if(!seen.add(linkId) || link.getOrdinal()!=ordinal++ || link.getStartOffset()<0
                        || link.getEndOffset()<=link.getStartOffset() || link.getEndOffset()>32768
                        || link.getTargetToken().isBlank() || link.getTargetToken().length()>1024
                        || !Set.of("RESOLVED","UNRESOLVED","AMBIGUOUS","TARGET_RETIRED").contains(link.getStatus())) throw invalid();
                boolean bound=Set.of("RESOLVED","TARGET_RETIRED").contains(link.getStatus());
                if(bound == link.getTargetNoteId().isEmpty()) throw invalid();
                if(bound) {
                    var target=id(link.getTargetNoteId());
                    if(link.getStatus().equals("RESOLVED")) resolved.add(new Link(linkId,target,link.getOrdinal()));
                }
                if(!link.getRequestedTargetId().isEmpty()) id(link.getRequestedTargetId());
            }
            var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(event.getPayload().toByteArray()));
            return new Links(eventId,spaceId,source,payload.getNoteVersion(),payload.getLinkVersion(),payload.getSourceState(),resolved,hash);
        } catch(InvalidProtocolBufferException | java.security.NoSuchAlgorithmException e) { throw invalid(); }
    }
    static UUID id(String text) {
        try { var id=UUID.fromString(text); if(id.version()==7 && id.variant()==2 && id.toString().equals(text)) return id; }
        catch(IllegalArgumentException ignored) {}
        throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid private graph event"); }
}
