package com.hhovhann.cpiassistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * At startup, makes sure a few popular SAP Help pages are in the store
 * ({@link SeedProperties}), so common questions do not start with
 * a download — and embeds the catalog titles, so the first miss does not wait
 * for that either. Pages already saved and fresh are not downloaded again.
 * A failure (no network) is logged, not fatal: the pages are then fetched on
 * the first question that needs them.
 */
@Component
public class SeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final KnowledgeService knowledge;
    private final SapHelpCatalog catalog;
    private final List<String> seedPages;
    private final boolean seedOnStartup;
    private final AtomicBoolean ready;
    private final AtomicInteger savedPages = new AtomicInteger();

    public SeedRunner(KnowledgeService knowledge, SapHelpCatalog catalog, SeedProperties properties) {
        this.knowledge = knowledge;
        this.catalog = catalog;
        this.seedPages = properties.seedPages();
        this.seedOnStartup = properties.seedOnStartup();
        this.ready = new AtomicBoolean(!seedOnStartup);
    }

    @Override
    public void run(String... args) {
        if (!seedOnStartup) {
            return;
        }
        long start = System.currentTimeMillis();
        for (String id : seedPages) {
            catalog.page(id).ifPresentOrElse(page -> {
                try {
                    KnowledgeService.Saved saved = knowledge.ensureSaved(page);
                    if (saved != KnowledgeService.Saved.MISSING) {
                        savedPages.incrementAndGet();
                    }
                    log.info("Seed page {}: {}", page.title(), saved);
                } catch (RuntimeException e) {
                    log.warn("Seed page {} failed, it will be fetched when needed: {}", page.title(), e.getMessage());
                }
            }, () -> log.warn("Seed page id {} is not in the catalog", id));
        }
        catalog.search("warm up", 1);
        log.info("=== Knowledge ready: {} of {} seed pages saved, {} catalog titles embedded, {} ms ===",
                savedPages.get(), seedPages.size(), catalog.size(), System.currentTimeMillis() - start);
        ready.set(true);
    }

    public boolean isReady() {
        return ready.get();
    }

    public int savedPages() {
        return savedPages.get();
    }

    public int seedPages() {
        return seedPages.size();
    }
}
