import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MiniSGBD {
    private static final int PAGE_SIZE = 4096;
    private static final int RECORD_SIZE = 100;
    private static final int RECORDS_PER_PAGE = PAGE_SIZE / RECORD_SIZE;
    private static final int PAGE_DATA_SIZE = RECORDS_PER_PAGE * RECORD_SIZE;

    private static final class PageFrame {
        final byte[] data;
        int pinCount;
        boolean dirty;

        PageFrame(byte[] data) {
            this.data = data;
        }
    }

    private final Path filePath;
    private final Map<Integer, PageFrame> bufferPool = new HashMap<>();
    private long recordCount;

    public MiniSGBD(String fileName) throws IOException {
        this.filePath = Paths.get(fileName);
        if (Files.notExists(filePath)) {
            Files.createFile(filePath);
        }
        long size = Files.size(filePath);
        if (size % RECORD_SIZE != 0) {
            throw new IOException("Corrupted data file: size not aligned with record size");
        }
        this.recordCount = size / RECORD_SIZE;
    }

    public synchronized byte[] fix(int pageId) throws IOException {
        if (pageId < 0) {
            throw new IllegalArgumentException("Page id must be non-negative");
        }
        PageFrame frame = bufferPool.get(pageId);
        if (frame == null) {
            frame = new PageFrame(loadPageData(pageId));
            bufferPool.put(pageId, frame);
        }
        frame.pinCount++;
        return frame.data;
    }

    public synchronized void unfix(int pageId) {
        PageFrame frame = bufferPool.get(pageId);
        if (frame == null || frame.pinCount == 0) {
            throw new IllegalStateException("Page " + pageId + " is not fixed");
        }
        frame.pinCount--;
    }

    public synchronized void use(int pageId) {
        PageFrame frame = bufferPool.get(pageId);
        if (frame == null) {
            throw new IllegalStateException("Page " + pageId + " is not in buffer");
        }
        frame.dirty = true;
    }

    public synchronized void force(int pageId) throws IOException {
        PageFrame frame = bufferPool.get(pageId);
        if (frame == null || !frame.dirty) {
            return;
        }
        long startRecord = (long) pageId * RECORDS_PER_PAGE;
        if (startRecord >= recordCount) {
            frame.dirty = false;
            return;
        }
        int recordsOnPage = (int) Math.min(RECORDS_PER_PAGE, recordCount - startRecord);
        int bytesToWrite = recordsOnPage * RECORD_SIZE;
        long offset = startRecord * RECORD_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.seek(offset);
            raf.write(frame.data, 0, bytesToWrite);
        }
        frame.dirty = false;
    }

    private byte[] loadPageData(int pageId) throws IOException {
        long startRecord = (long) pageId * RECORDS_PER_PAGE;
        long offset = startRecord * RECORD_SIZE;
        byte[] pageData = new byte[PAGE_DATA_SIZE];
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            if (offset >= raf.length()) {
                return pageData;
            }
            raf.seek(offset);
            int bytesToRead = (int) Math.min(pageData.length, raf.length() - offset);
            int totalRead = 0;
            while (totalRead < bytesToRead) {
                int read = raf.read(pageData, totalRead, bytesToRead - totalRead);
                if (read < 0) {
                    break;
                }
                totalRead += read;
            }
        }
        return pageData;
    }

    private void insertRecordInternal(String data, boolean sync) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("Record data cannot be null");
        }

        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        if (payload.length > RECORD_SIZE) {
            throw new IllegalArgumentException("Record data exceeds fixed size of " + RECORD_SIZE + " bytes");
        }

        byte[] record = new byte[RECORD_SIZE];
        System.arraycopy(payload, 0, record, 0, payload.length);

        long newRecordId = recordCount;
        int pageId = (int) (newRecordId / RECORDS_PER_PAGE);
        int recordOffset = (int) (newRecordId % RECORDS_PER_PAGE) * RECORD_SIZE;

        byte[] pageData = fix(pageId);
        try {
            System.arraycopy(record, 0, pageData, recordOffset, RECORD_SIZE);
            use(pageId);
        } finally {
            unfix(pageId);
        }

        recordCount++;
        if (sync) {
            force(pageId);
        }
    }

    public void insertRecord(String data) throws IOException {
        insertRecordInternal(data, false);
    }

    public void insertRecordSync(String data) throws IOException {
        insertRecordInternal(data, true);
    }

    public synchronized String readRecord(int recordId) throws IOException {
        if (recordId < 0 || recordId >= recordCount) {
            throw new IllegalArgumentException("Record id " + recordId + " is out of bounds");
        }

        int pageId = recordId / RECORDS_PER_PAGE;
        int recordOffset = (recordId % RECORDS_PER_PAGE) * RECORD_SIZE;
        byte[] pageData = fix(pageId);
        try {
            return decodeRecord(pageData, recordOffset);
        } finally {
            unfix(pageId);
        }
    }

    public synchronized List<String> getPage(int pageNumber) throws IOException {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("Page number must be non-negative");
        }

        long startRecord = (long) pageNumber * RECORDS_PER_PAGE;
        if (startRecord >= recordCount) {
            return Collections.emptyList();
        }

        int recordsToRead = (int) Math.min(RECORDS_PER_PAGE, recordCount - startRecord);
        byte[] pageData = fix(pageNumber);
        try {
            List<String> page = new ArrayList<>(recordsToRead);
            for (int i = 0; i < recordsToRead; i++) {
                int offset = i * RECORD_SIZE;
                page.add(decodeRecord(pageData, offset));
            }
            return page;
        } finally {
            unfix(pageNumber);
        }
    }

    private String decodeRecord(byte[] pageData, int offset) {
        int end = offset;
        int limit = offset + RECORD_SIZE;
        while (end < limit && pageData[end] != 0) {
            end++;
        }
        return new String(pageData, offset, end - offset, StandardCharsets.UTF_8);
    }

    public synchronized long getRecordCount() {
        return recordCount;
    }

    public static void main(String[] args) throws IOException {
        Path dbPath = Paths.get("etudiants.db");
        Files.deleteIfExists(dbPath);

        MiniSGBD db = new MiniSGBD("etudiants.db");

        for (int i = 1; i <= 105; i++) {
            if (i % 10 == 0) {
                db.insertRecordSync("Etudiant " + i);
            } else {
                db.insertRecord("Etudiant " + i);
            }
        }

        int pageCount = (int) ((db.getRecordCount() + RECORDS_PER_PAGE - 1) / RECORDS_PER_PAGE);
        for (int pageId = 0; pageId < pageCount; pageId++) {
            db.force(pageId);
        }

        System.out.println("Enregistrement 42 : " + db.readRecord(41));
        System.out.println("Page 1 : " + db.getPage(0));
        System.out.println("Page 2 : " + db.getPage(1));
        System.out.println("Page 3 : " + db.getPage(2));
    }
}
