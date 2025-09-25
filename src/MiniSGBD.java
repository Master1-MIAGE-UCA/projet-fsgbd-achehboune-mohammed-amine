import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MiniSGBD {
    private static final int PAGE_SIZE = 4096;
    private static final int RECORD_SIZE = 100;
    private static final int RECORDS_PER_PAGE = PAGE_SIZE / RECORD_SIZE;

    private final Path filePath;

    public MiniSGBD(String fileName) throws IOException {
        this.filePath = Paths.get(fileName);
        if (Files.notExists(filePath)) {
            Files.createFile(filePath);
        }
    }

    public void insertRecord(String data) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("Record data cannot be null");
        }

        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        if (payload.length > RECORD_SIZE) {
            throw new IllegalArgumentException("Record data exceeds fixed size of " + RECORD_SIZE + " bytes");
        }

        byte[] record = new byte[RECORD_SIZE];
        System.arraycopy(payload, 0, record, 0, payload.length);

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(record);
        }
    }

    public String readRecord(int recordId) throws IOException {
        if (recordId < 0) {
            throw new IllegalArgumentException("Record id must be non-negative");
        }

        long offset = (long) recordId * RECORD_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            if (offset >= raf.length()) {
                throw new IllegalArgumentException("Record id " + recordId + " is out of bounds");
            }

            raf.seek(offset);
            byte[] buffer = new byte[RECORD_SIZE];
            int readBytes = raf.read(buffer);
            if (readBytes != RECORD_SIZE) {
                throw new IOException("Unexpected end of file while reading record");
            }
            return decodeRecord(buffer);
        }
    }

    public List<String> getPage(int pageNumber) throws IOException {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("Page number must be non-negative");
        }

        long totalRecords = Files.size(filePath) / RECORD_SIZE;
        long startRecord = (long) pageNumber * RECORDS_PER_PAGE;
        if (startRecord >= totalRecords) {
            return Collections.emptyList();
        }

        int recordsToRead = (int) Math.min(RECORDS_PER_PAGE, totalRecords - startRecord);
        List<String> page = new ArrayList<>(recordsToRead);

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek(startRecord * RECORD_SIZE);
            for (int i = 0; i < recordsToRead; i++) {
                byte[] buffer = new byte[RECORD_SIZE];
                int readBytes = raf.read(buffer);
                if (readBytes != RECORD_SIZE) {
                    throw new IOException("Unexpected end of file while reading page");
                }
                page.add(decodeRecord(buffer));
            }
        }

        return page;
    }

    private String decodeRecord(byte[] recordBytes) {
        int end = 0;
        while (end < recordBytes.length && recordBytes[end] != 0) {
            end++;
        }
        return new String(recordBytes, 0, end, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        MiniSGBD db = new MiniSGBD("etudiants.db");

        for (int i = 1; i <= 105; i++) {
            db.insertRecord("Etudiant " + i);
        }

        System.out.println("Enregistrement 42 : " + db.readRecord(41));
        System.out.println("Page 1 : " + db.getPage(0));
        System.out.println("Page 2 : " + db.getPage(1));
        System.out.println("Page 3 : " + db.getPage(2));
    }
}
