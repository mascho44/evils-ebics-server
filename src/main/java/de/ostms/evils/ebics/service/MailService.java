package de.ostms.evils.ebics.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Duenner Wrapper um JavaMailSender: ein SMTP-Fehler (z.B. kein Server erreichbar, insbesondere in
 * dev/test) darf die Freigabe/Ablehnung eines Mandanten-Antrags nie abbrechen - der Mandant ist
 * bereits angelegt bzw. die Ablehnung bereits vermerkt, nur die Benachrichtigung schlaegt fehl.
 * Deshalb wird hier verschluckt und nur geloggt statt weitergeworfen (Benachrichtigungen sind nachrangig).
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String absender;

    public MailService(JavaMailSender mailSender, @Value("${evils.mail.absender}") String absender) {
        this.mailSender = mailSender;
        this.absender = absender;
    }

    public void senden(String empfaenger, String betreff, String text) {
        try {
            SimpleMailMessage nachricht = new SimpleMailMessage();
            nachricht.setFrom(absender);
            nachricht.setTo(empfaenger);
            nachricht.setSubject(betreff);
            nachricht.setText(text);
            mailSender.send(nachricht);
        } catch (MailException e) {
            log.warn("E-Mail an {} konnte nicht gesendet werden: {}", empfaenger, e.getMessage());
        }
    }
}
