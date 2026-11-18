package de.ostms.evils.ebics.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
@Entity
public class ReportDownload {
    @Id private String id;
    private String userId;
    private String kind;
    private Instant createdAt;
    private Integer receiptCode;
    @ElementCollection(fetch=FetchType.EAGER) private List<Long> orderIds=new ArrayList<>();
    @ElementCollection(fetch=FetchType.EAGER) private Set<String> eventIds=new HashSet<>();
    protected ReportDownload(){}
    public ReportDownload(String id,String userId,String kind,List<Long> orderIds,Set<String> eventIds){this.id=id;this.userId=userId;this.kind=kind;this.orderIds=new ArrayList<>(orderIds);this.eventIds=new HashSet<>(eventIds);createdAt=Instant.now();}
    public String getId(){return id;} public String getUserId(){return userId;} public String getKind(){return kind;} public Instant getCreatedAt(){return createdAt;}
    public Integer getReceiptCode(){return receiptCode;} public void receipt(int code){receiptCode=code;}
    public List<Long> getOrderIds(){return orderIds;} public Set<String> getEventIds(){return eventIds;}
}
