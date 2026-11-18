package de.ostms.evils.ebics.events;
import jakarta.persistence.*;
import java.time.Instant;
@Entity
@Table(name="event_outbox",indexes=@Index(name="event_outbox_due",columnList="status,next_attempt_at"))
public class EventOutbox {
    @Id private String id;
    @Column(nullable=false,columnDefinition="text") private String eventJson;
    @Column(nullable=false) private String status="PENDING";
    private int attempts;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    private Instant deliveredAt;
    private String lastError;
    protected EventOutbox() {}
    public EventOutbox(String id,String eventJson,Instant now){this.id=id;this.eventJson=eventJson;this.nextAttemptAt=now;}
    public String getId(){return id;} public String getEventJson(){return eventJson;}
    public String getStatus(){return status;} public int getAttempts(){return attempts;}
    public Instant getNextAttemptAt(){return nextAttemptAt;} public Instant getDeliveredAt(){return deliveredAt;}
    public String getLastError(){return lastError;}
    public boolean due(Instant now){return "PENDING".equals(status)&&!nextAttemptAt.isAfter(now);}
    public void delivered(Instant now){attempts++;status="DELIVERED";deliveredAt=now;lastError=null;}
    public void failed(Instant now,String error,int maxAttempts){attempts++;lastError=error;
        if(attempts>=maxAttempts)status="DEAD";
        else nextAttemptAt=now.plusSeconds(Math.min(3600L,5L*(1L<<Math.min(attempts-1,10))));
    }
    public void replay(Instant now){
        if(!"DEAD".equals(status)) throw new IllegalStateException("Only DEAD events can be replayed");
        status="PENDING"; attempts=0; nextAttemptAt=now; lastError=null;
    }
}
