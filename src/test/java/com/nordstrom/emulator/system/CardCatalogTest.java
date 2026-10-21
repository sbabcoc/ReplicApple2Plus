package com.nordstrom.emulator.system;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardCatalogTest {

    private static List<String> names(CardCatalog.Catalog catalog) {
        return catalog.cards().stream().map(CardCatalog.CardDescription::shortName).toList();
    }

    @Test
    void describesEveryBuiltInCard() {
        CardCatalog.Catalog catalog = CardCatalog.read(CardCatalogTest.class.getClassLoader());
        assertTrue(names(catalog).containsAll(List.of("disk2", "languageCard", "saturn128", "hostfiles", "videoterm")),
            names(catalog).toString());
        assertTrue(catalog.problems().isEmpty(), catalog.problems().toString());
        CardCatalog.CardDescription disk = catalog.find("disk2").orElseThrow();
        assertEquals("Disk II", disk.displayName());
        assertEquals(List.of("drive1", "drive2"), disk.parameters().stream().map(CardParameter::name).toList());
        assertEquals(CardParameter.Kind.FILE, disk.parameter("drive1").orElseThrow().kind());
    }

    @Test
    void aProviderThatCantBeBuiltOrDescribedIsLeftOutAndTheRestRemain(@TempDir Path dir) throws IOException {
        ClassLoader loader = TestCards.loader(dir,
            "com.nordstrom.emulator.system.NoSuchCard",
            TestCards.ThrowingCard.class.getName(),
            TestCards.NamelessCard.class.getName(),
            TestCards.DoubledCard.class.getName(),
            TestCards.CountCard.class.getName());
        CardCatalog.Catalog catalog = CardCatalog.read(loader);

        assertTrue(names(catalog).containsAll(List.of("testCount", "disk2")), names(catalog).toString());
        assertTrue(names(catalog).stream().noneMatch(name -> name.equals("testThrowing")
            || name.equals("testNameless") || name.equals("testDoubled")), names(catalog).toString());
        assertEquals(4, catalog.problems().size(), catalog.problems().toString());
        String reported = catalog.problems().toString();
        assertTrue(reported.contains("display name is blank"), reported);
        assertTrue(reported.contains("\"dir\" twice"), reported);
    }

    @Test
    void twoCardsClaimingOneShortNameAreBothLeftOutAsAConflict(@TempDir Path dir) throws IOException {
        ClassLoader loader = TestCards.loader(dir, TestCards.ClashA.class.getName(), TestCards.ClashB.class.getName());
        CardCatalog.Catalog catalog = CardCatalog.read(loader);
        assertTrue(catalog.find("testClash").isEmpty());
        String conflict = catalog.conflicts().get("testClash");
        assertTrue(conflict.contains("ClashA") && conflict.contains("ClashB"), conflict);
    }

    @Test
    void theSameClassListedTwiceIsOneCard(@TempDir Path dir) throws IOException {
        ClassLoader loader = TestCards.loader(dir, TestCards.CountCard.class.getName(), TestCards.CountCard.class.getName());
        CardCatalog.Catalog catalog = CardCatalog.read(loader);
        assertEquals(1, names(catalog).stream().filter("testCount"::equals).count());
        assertTrue(catalog.conflicts().isEmpty());
    }
}
