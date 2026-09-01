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
 * <p>Thymeleaf inside this process, so {@code spring-boot:run} serves the pages with no
 * second build step, no CORS and no separate deployment artifact.
 *
 * <p>The index links to a diff with both ordinals named, which is what makes the URL
 * stable: the same link shows the same two revisions after a third one arrives, where the
 * defaults would silently move to the newest pair -- while staying optional, so that
 * "what changed last" is reachable by hand.
 *
 * <p>Thin on purpose. The floor on {@code minRevisions} lives in {@link ChangeFilter}, so
 * a hand-written {@code minRevisions=0} cannot widen the listing past what it means.
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
