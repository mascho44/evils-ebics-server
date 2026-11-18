package de.ostms.evils.ebics.events;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
public class EventReplayAudit {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String eventId;
    private String administrator;
    private Instant replayedAt;
    private int previousAttempts;
    private String previousError;
    protected EventReplayAudit() {}
    public EventReplayAudit(EventOutbox row,String actor) {
        eventId=row.getId(); administrator=actor; replayedAt=Instant.now();
        previousAttempts=row.getAttempts(); previousError=row.getLastError();
    }
}
