import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MiniSGBD {
    private static final int PAGE_SIZE = 4096;
    private static final int RECORD_SIZE = 100;
    private static final int RECORDS_PER_PAGE = PAGE_SIZE / RECORD_SIZE;
    private static final int PAGE_DATA_SIZE = RECORDS_PER_PAGE * RECORD_SIZE;

    private static final class PageFrame {
        final byte[] data;
        int pinCount;
        boolean dirty;
        boolean transactional;

        PageFrame(byte[] data) {
            this.data = data;
        }
    }

    private final Path filePath;
    // TIA (Tampon d'Images Après) - Buffer contenant les nouvelles valeurs
    private final Map<Integer, PageFrame> bufferPool = new HashMap<>();
    // TIV (Tampon d'Images Avant) - Buffer contenant les anciennes valeurs avant modification
    private final Map<Integer, byte[]> tiv = new HashMap<>();
    // Table des verrous sur les enregistrements
    private final Set<Integer> locks = new HashSet<>();

    private long recordCount;
    private boolean inTransaction;
    private long transactionStartRecordCount = -1;

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

    public synchronized void begin() {
        if (inTransaction) {
            try {
                commit();
            } catch (IOException e) {
                throw new RuntimeException("Failed to commit active transaction before starting a new one", e);
            }
        }
        inTransaction = true;
        transactionStartRecordCount = recordCount;
    }

    public synchronized void commit() throws IOException {
        if (!inTransaction) {
            return;
        }
        // Forcer sur disque toutes les pages transactionnelles modifiees (FORCE)
        for (Map.Entry<Integer, PageFrame> entry : bufferPool.entrySet()) {
            int pageId = entry.getKey();
            PageFrame frame = entry.getValue();
            if (frame.dirty && frame.transactional) {
                writePageToDisk(pageId, frame);
                frame.dirty = false;
                frame.transactional = false;
            }
        }

        // Vider le TIV (les anciennes valeurs ne sont plus necessaires)
        tiv.clear();

        // Liberer tous les verrous poses par la transaction
        locks.clear();

        inTransaction = false;
        transactionStartRecordCount = -1;
    }

    public synchronized void rollback() {
        if (!inTransaction) {
            return;
        }
        recordCount = transactionStartRecordCount;
        transactionStartRecordCount = -1;

        // Restaurer les anciennes valeurs depuis le TIV vers le TIA
        for (Map.Entry<Integer, byte[]> entry : tiv.entrySet()) {
            int pageId = entry.getKey();
            byte[] oldData = entry.getValue();
            PageFrame frame = bufferPool.get(pageId);
            if (frame != null) {
                // Restaurer les données originales
                System.arraycopy(oldData, 0, frame.data, 0, oldData.length);
                frame.dirty = false;
                frame.transactional = false;
            }
        }

        // Vider le TIV
        tiv.clear();

        // Liberer tous les verrous
        locks.clear();

        // Supprimer les pages purement transactionnelles (nouvelles pages sans TIV)
        Iterator<Map.Entry<Integer, PageFrame>> iterator = bufferPool.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, PageFrame> entry = iterator.next();
            PageFrame frame = entry.getValue();
            if (frame.transactional) {
                if (frame.pinCount != 0) {
                    throw new IllegalStateException("Cannot rollback while page " + entry.getKey() + " is still pinned");
                }
                iterator.remove();
            }
        }
        inTransaction = false;
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
        if (inTransaction) {
            frame.transactional = true;
        }
    }

    public synchronized void force(int pageId) throws IOException {
        PageFrame frame = bufferPool.get(pageId);
        if (frame == null || !frame.dirty) {
            return;
        }
        if (frame.transactional && inTransaction) {
            return;
        }
        writePageToDisk(pageId, frame);
        frame.dirty = false;
        frame.transactional = false;
    }

    private void writePageToDisk(int pageId, PageFrame frame) throws IOException {
        long startRecord = (long) pageId * RECORDS_PER_PAGE;
        if (startRecord >= recordCount) {
            return;
        }
        long remainingRecords = recordCount - startRecord;
        int recordsOnPage = (int) Math.min(RECORDS_PER_PAGE, remainingRecords);
        if (recordsOnPage <= 0) {
            return;
        }
        int bytesToWrite = recordsOnPage * RECORD_SIZE;
        long offset = startRecord * RECORD_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            raf.seek(offset);
            raf.write(frame.data, 0, bytesToWrite);
        }
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

    /**
     * Met a jour un enregistrement existant.
     * En transaction: verrouille l'enregistrement et sauvegarde l'ancienne valeur dans le TIV.
     * @param recordId l'identifiant de l'enregistrement a modifier
     * @param newData les nouvelles donnees
     * @throws IOException en cas d'erreur d'E/S
     * @throws IllegalStateException si l'enregistrement est deja verrouille
     */
    public synchronized void updateRecord(int recordId, String newData) throws IOException {
        if (recordId < 0 || recordId >= recordCount) {
            throw new IllegalArgumentException("Record id " + recordId + " is out of bounds");
        }
        if (newData == null) {
            throw new IllegalArgumentException("Record data cannot be null");
        }

        byte[] payload = newData.getBytes(StandardCharsets.UTF_8);
        if (payload.length > RECORD_SIZE) {
            throw new IllegalArgumentException("Record data exceeds fixed size of " + RECORD_SIZE + " bytes");
        }

        // Verifier si l'enregistrement est deja verrouille
        if (locks.contains(recordId)) {
            throw new IllegalStateException("Record " + recordId + " is already locked by another transaction");
        }

        int pageId = recordId / RECORDS_PER_PAGE;
        int recordOffset = (recordId % RECORDS_PER_PAGE) * RECORD_SIZE;

        byte[] pageData = fix(pageId);
        try {
            // Si en transaction, sauvegarder l'image avant dans le TIV
            if (inTransaction) {
                // Sauvegarder la page entiere dans le TIV si pas deja fait
                if (!tiv.containsKey(pageId)) {
                    byte[] beforeImage = Arrays.copyOf(pageData, pageData.length);
                    tiv.put(pageId, beforeImage);
                }
                // Poser un verrou sur l'enregistrement
                locks.add(recordId);
            }

            // Preparer le nouvel enregistrement (padding avec des zeros)
            byte[] record = new byte[RECORD_SIZE];
            System.arraycopy(payload, 0, record, 0, payload.length);

            // Modifier la donnee dans le TIA uniquement
            System.arraycopy(record, 0, pageData, recordOffset, RECORD_SIZE);
            use(pageId);
        } finally {
            unfix(pageId);
        }
    }

    /**
     * Verifie si un enregistrement est verrouille.
     * @param recordId l'identifiant de l'enregistrement
     * @return true si l'enregistrement est verrouille
     */
    public synchronized boolean isLocked(int recordId) {
        return locks.contains(recordId);
    }

    /**
     * Lit un enregistrement.
     * Politique de lecture:
     * - Pas de transaction: lecture depuis TIA
     * - Transaction en cours + pas de verrou: lecture depuis TIA
     * - Transaction en cours + enregistrement verrouille: lecture depuis TIV (ancienne valeur)
     */
    public synchronized String readRecord(int recordId) throws IOException {
        if (recordId < 0 || recordId >= recordCount) {
            throw new IllegalArgumentException("Record id " + recordId + " is out of bounds");
        }

        int pageId = recordId / RECORDS_PER_PAGE;
        int recordOffset = (recordId % RECORDS_PER_PAGE) * RECORD_SIZE;

        // Si en transaction et l'enregistrement est verrouille, lire depuis le TIV
        if (inTransaction && locks.contains(recordId)) {
            byte[] tivData = tiv.get(pageId);
            if (tivData != null) {
                return decodeRecord(tivData, recordOffset);
            }
        }

        // Sinon, lire depuis le TIA (buffer normal)
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

        System.out.println("=== TD1-TD3: Tests de base ===");
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

        // Demonstration rollback insertion
        db.begin();
        db.insertRecord("Etudiant 200");
        db.insertRecord("Etudiant 201");
        db.rollback();
        System.out.println("Count apres rollback (attendu 105) : " + db.getRecordCount());

        // Demonstration commit insertion
        db.begin();
        db.insertRecord("Etudiant 202");
        db.insertRecord("Etudiant 203");
        db.commit();
        System.out.println("Count apres commit (attendu 107) : " + db.getRecordCount());

        System.out.println("Enregistrement 106 : " + db.readRecord(105));
        System.out.println("Enregistrement 107 : " + db.readRecord(106));

        System.out.println("\n=== TD4: Tests TIV, TIA et Verrouillage ===");

        // Test 1: UPDATE avec ROLLBACK - restauration depuis TIV
        System.out.println("\n--- Test 1: UPDATE avec ROLLBACK ---");
        System.out.println("Avant modification - Enregistrement 0 : " + db.readRecord(0));

        db.begin();
        db.updateRecord(0, "Etudiant MODIFIE");
        System.out.println("Apres UPDATE (en transaction) - Enregistrement 0 : " + db.readRecord(0));
        System.out.println("Enregistrement 0 verrouille ? " + db.isLocked(0));

        db.rollback();
        System.out.println("Apres ROLLBACK - Enregistrement 0 : " + db.readRecord(0));
        System.out.println("Enregistrement 0 verrouille ? " + db.isLocked(0));

        // Test 2: UPDATE avec COMMIT - persistence des modifications
        System.out.println("\n--- Test 2: UPDATE avec COMMIT ---");
        System.out.println("Avant modification - Enregistrement 1 : " + db.readRecord(1));

        db.begin();
        db.updateRecord(1, "Etudiant 2 MODIFIE PERMANENT");
        System.out.println("Apres UPDATE (en transaction) - Enregistrement 1 : " + db.readRecord(1));

        db.commit();
        System.out.println("Apres COMMIT - Enregistrement 1 : " + db.readRecord(1));

        // Test 3: Lecture coherente avec TIV
        System.out.println("\n--- Test 3: Lecture coherente (TIV) ---");
        System.out.println("Valeur initiale - Enregistrement 2 : " + db.readRecord(2));

        db.begin();
        db.updateRecord(2, "NOUVELLE VALEUR");
        // En transaction avec verrou, readRecord retourne l'ancienne valeur (TIV)
        System.out.println("Lecture pendant transaction (depuis TIV) : " + db.readRecord(2));
        db.commit();
        System.out.println("Apres COMMIT (nouvelle valeur) : " + db.readRecord(2));

        // Test 4: Multiple updates dans une transaction
        System.out.println("\n--- Test 4: Updates multiples + ROLLBACK ---");
        System.out.println("Avant - Enregistrement 10 : " + db.readRecord(10));
        System.out.println("Avant - Enregistrement 11 : " + db.readRecord(11));

        db.begin();
        db.updateRecord(10, "Record 10 MODIFIE");
        db.updateRecord(11, "Record 11 MODIFIE");
        System.out.println("Pendant transaction - Enregistrement 10 : " + db.readRecord(10));
        System.out.println("Pendant transaction - Enregistrement 11 : " + db.readRecord(11));

        db.rollback();
        System.out.println("Apres ROLLBACK - Enregistrement 10 : " + db.readRecord(10));
        System.out.println("Apres ROLLBACK - Enregistrement 11 : " + db.readRecord(11));

        // Test 5: Verification du verrouillage (tentative de double lock)
        System.out.println("\n--- Test 5: Detection de verrouillage ---");
        db.begin();
        db.updateRecord(20, "Premier UPDATE");
        System.out.println("Enregistrement 20 verrouille : " + db.isLocked(20));
        try {
            db.updateRecord(20, "Deuxieme UPDATE - devrait echouer");
            System.out.println("ERREUR: Le double verrouillage aurait du echouer!");
        } catch (IllegalStateException e) {
            System.out.println("OK: Double verrouillage detecte - " + e.getMessage());
        }
        db.rollback();

        System.out.println("\n=== Tous les tests TD4 termines avec succes! ===");
    }
}
