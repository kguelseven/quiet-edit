package org.korhan.quietedit.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * The reading interface: two server-rendered pages over the same services the REST
 * endpoints use.
 *
 * <p>Thymeleaf inside this process, so {@code spring-boot:run} serves the pages with
 * no second build step, no CORS and no separate deployment artifact. The use case is
 * reading -- nothing here posts, and no page needs state that a URL cannot carry.
 *
 * <h2>Two pages, and why the URLs look like this</h2>
 * <ul>
 *   <li>{@code GET /} -- the documents observed to change, most recent change first,
 *       with the filters as query parameters so a narrowed listing can be linked.</li>
 *   <li>{@code GET /documents/{id}/diff?from=&to=} -- one diff. The index links to it
 *       with both ordinals named, which is what makes the URL stable: the same link
 *       shows the same two revisions after a third one arrives, while the defaults
 *       would silently move to the newest pair.</li>
 * </ul>
 * The ordinals stay optional so that "what changed last" is still reachable by hand,
 * matching the REST endpoint.
 *
 * <p>Thin on purpose: it binds the query string into a {@link ChangeFilter} and hands
 * the result of {@link ChangeBrowserService} to a template. The filter's own floor on
 * {@code minRevisions} lives in the record, so a hand-written {@code minRevisions=0}
 * cannot widen the listing past what it means.
 */
@Controller
public class ChangeBrowserController {

    private final ChangeBrowserService service;

    public ChangeBrowserController(ChangeBrowserService service) {
        this.service = service;
    }

    @GetMapping("/")
    String index(@RequestParam(name = "feed", required = false) UUID feed,
                 @RequestParam(name = "within", defaultValue = "ALL") TimeWindow within,
                 @RequestParam(name = "minRevisions", defaultValue = "2") int minRevisions,
                 @RequestParam(name = "rewrittenOnly", defaultValue = "false") boolean rewrittenOnly,
                 Model model) {
        model.addAttribute("view",
                service.index(new ChangeFilter(feed, within, minRevisions, rewrittenOnly)));
        model.addAttribute("windows", TimeWindow.values());
        return "index";
    }

    @GetMapping("/documents/{documentId}/diff")
    String diff(@PathVariable UUID documentId,
                @RequestParam(required = false) Integer from,
                @RequestParam(required = false) Integer to,
                Model model) {
        model.addAttribute("view", service.diff(documentId, from, to));
        return "diff";
    }
}
