package provider.wz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.Data;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XMLDomMapleDataTest {
    private static final int CATEGORIES = 8;
    private static final int ITEMS = 300;

    @TempDir
    Path dir;

    /** A file shaped like String.wz/Eqp.img.xml: categories of items, each with a name. */
    private Path writeDataFile() throws Exception {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        xml.append("<imgdir name=\"Eqp.img\">\n  <imgdir name=\"Eqp\">\n");
        for (int c = 0; c < CATEGORIES; c++) {
            xml.append("    <imgdir name=\"Cat").append(c).append("\">\n");
            for (int i = 0; i < ITEMS; i++) {
                int id = itemId(c, i);
                xml.append("      <imgdir name=\"").append(id).append("\">\n")
                        .append("        <string name=\"name\" value=\"Item ").append(id).append("\"/>\n")
                        .append("        <int name=\"price\" value=\"").append(id % 1000).append("\"/>\n")
                        .append("      </imgdir>\n");
            }
            xml.append("    </imgdir>\n");
        }
        xml.append("  </imgdir>\n</imgdir>\n");
        Path file = dir.resolve("Eqp.img.xml");
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    private static int itemId(int category, int item) {
        return 1000000 + category * 10000 + item;
    }

    private Data load(Path file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            return new XMLDomMapleData(fis, dir);
        }
    }

    @Test
    void readsValuesByPath() throws Exception {
        Data root = load(writeDataFile());

        assertEquals("Eqp.img", root.getName());
        assertEquals("Item 1020007", root.getChildByPath("Eqp/Cat2/1020007/name").getData());
        assertEquals(7, root.getChildByPath("Eqp/Cat2/1020007/price").getData());
        assertEquals(CATEGORIES, root.getChildByPath("Eqp").getChildren().size());
        assertNull(root.getChildByPath("Eqp/Cat2/1"));
    }

    /**
     * One loaded file is shared by everything that reads it (item names, quests, skills), so it has to give the same
     * answers when many threads read it at once, from the first read on.
     */
    @Test
    void sameAnswersWhenManyThreadsReadOneFile() throws Exception {
        Path file = writeDataFile();
        final int threads = 16, rounds = 25;
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        for (int round = 0; round < rounds; round++) {
            Data root = load(file);     // fresh each round: nothing of it has been read yet
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 400; n++) {
                            int c = (seed + n) % CATEGORIES, i = (seed * 37 + n * 11) % ITEMS, id = itemId(c, i);
                            Data item = root.getChildByPath("Eqp/Cat" + c + "/" + id);
                            if (item == null) {
                                failures.add("item " + id + " not found");
                                continue;
                            }
                            Object name = item.getChildByPath("name").getData();
                            if (!("Item " + id).equals(name)) {
                                failures.add("item " + id + " named " + name);
                            }
                            if (n % 50 == 0 && root.getChildByPath("Eqp/Cat" + c).getChildren().size() != ITEMS) {
                                failures.add("category " + c + " lost children");
                            }
                        }
                    } catch (Throwable e) {
                        failures.add(e.toString());
                    }
                });
                workers.add(worker);
                worker.start();
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join(TimeUnit.SECONDS.toMillis(30));
            }
        }

        assertTrue(failures.isEmpty(), failures.size() + " wrong reads, first: " + failures.peek());
    }
}
