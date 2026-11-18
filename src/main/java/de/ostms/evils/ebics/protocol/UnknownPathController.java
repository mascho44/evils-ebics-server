package de.ostms.evils.ebics.protocol;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers a 404 (e.g. a client or scanner probing a guessed path like "/ebicsveu") with the same
 * EVILS-branded page as GET /ebicsweb instead of Spring Boot's bare {"timestamp":...,"status":404}
 * JSON. Implemented as a proper ErrorController mapped to "/error" - Spring Boot's own error
 * dispatch mechanism only forwards here once every real mapping (static welcome page, "/login",
 * "/ebicsweb", the admin API, ...) has already had its chance, so unlike an earlier "/**"
 * @GetMapping attempt, this can never shadow those.
 *
 * Not GET-only: BasicAuthenticationEntryPoint.commence() also goes through response.sendError(),
 * which Spring Boot forwards to "/error" with the ORIGINAL request method preserved - a GET-only
 * mapping here made every unauthenticated POST (e.g. the documented curl bank-keys calls) come
 * back 405 instead of the intended 401. Only GET gets the branded HTML; anything else keeps
 * whatever status/headers (e.g. WWW-Authenticate) the entry point already set, with an empty body.
 */
@RestController
public class UnknownPathController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<String> fehlerseite(HttpServletRequest request) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = statusAttr instanceof Integer code ? HttpStatus.resolve(code) : null;
        if (status == null) status = HttpStatus.NOT_FOUND;

        if (!HttpMethod.GET.matches(request.getMethod())) {
            return ResponseEntity.status(status).build();
        }

        Object pfadAttr = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String pfad = pfadAttr != null ? pfadAttr.toString() : request.getRequestURI();

        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body("""
                <!doctype html>
                <html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>EVILS EBICS Server</title><style>
                body{margin:0;min-height:100vh;display:grid;place-items:center;background:#09111f;color:#e8eef9;font:16px/1.55 system-ui,sans-serif}
                main{max-width:620px;margin:24px;padding:34px;border:1px solid #243550;border-radius:16px;background:#111d31;box-shadow:0 20px 50px #0005}
                h1{margin:0 0 8px;font-size:25px}strong{color:#4f9cff}p{color:#b8c7df}code{display:block;margin:20px 0;padding:14px;border-radius:9px;background:#09111f;color:#b7f6d4;overflow-wrap:anywhere}.badge{color:#ffb457;font-size:13px}
                </style></head><body><main><div class="badge">● %s</div><h1><strong>EVILS</strong> EBICS Server</h1>
                <p>Der aufgerufene Pfad ist keine gueltige EBICS- oder Admin-Adresse dieses Servers.</p>
                <code>%s</code>
                <p>Der echte EBICS-Endpunkt liegt unter <strong>/ebicsweb</strong> (POST, Content-Type: application/xml).</p>
                </main></body></html>
                """.formatted(escape(status.toString()), escape(pfad)));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
