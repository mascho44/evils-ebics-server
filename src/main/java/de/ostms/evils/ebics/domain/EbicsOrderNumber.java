package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;

/** Durable allocation prevents reused wire references after restarts or concurrent uploads. */
@Entity
public class EbicsOrderNumber {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    public String value(){
        if(id==null||id>26L*36*36*36)throw new IllegalStateException("EBICS order number space exhausted");
        long n=id-1;
        return String.valueOf((char)('A'+n/(36*36*36)))+String.format("%3s",Long.toString(n%(36*36*36),36)).replace(' ','0').toUpperCase(java.util.Locale.ROOT);
    }
}
