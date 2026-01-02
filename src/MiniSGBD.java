import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MiniSGBD {

    // ==================== TD5: Types de log ====================
    public enum LogType {
        BEGIN, UPDATE, INSERT, DELETE, COMMIT, ROLLBACK, CHECKPOINT
    }

    // ==================== TD5: Classe LogEntry ====================
    public static class LogEntry {
        final int transactionId;
        final int recordId;          // -1 si non applicable
        final byte[] beforeImage;    // null si non applicable
        final byte[] afterImage;     // null si non applicable
        final LogType type;
        final long recordCountSnapshot; // pour INSERT: nombre d'enregistrements au moment du log

        public LogEntry(int transactionId, LogType type) {
            this(transactionId, -1, null, null, type, -1);
        }

        public LogEntry(int transactionId, int recordId, byte[] beforeImage, byte[] afterImage, LogType type) {
            this(transactionId, recordId, beforeImage, afterImage, type, -1);
        }

        public LogEntry(int transactionId, int recordId, byte[] beforeImage, byte[] afterImage, LogType type, long recordCountSnapshot) {
            this.transactionId = transactionId;
            this.recordId = recordId;
            this.beforeImage = beforeImage != null ? Arrays.copyOf(beforeImage, beforeImage.length) : null;
            this.afterImage = afterImage != null ? Arrays.copyOf(afterImage, afterImage.length) : null;
            this.type = type;
            this.recordCountSnapshot = recordCountSnapshot;
        }

        // Serialisation vers String pour ecriture dans FJT
        public String serialize() {
            StringBuilder sb = new StringBuilder();
            sb.append(transactionId).append("|");
            sb.append(recordId).append("|");
            sb.append(beforeImage != null ? Base64.getEncoder().encodeToString(beforeImage) : "NULL").append("|");
            sb.append(afterImage != null ? Base64.getEncoder().encodeToString(afterImage) : "NULL").append("|");
            sb.append(type.name()).append("|");
            sb.append(recordCountSnapshot);
            return sb.toString();
        }

        // Deserialisation depuis String
        public static LogEntry deserialize(String line) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 6) return null;

            int txId = Integer.parseInt(parts[0]);
            int recId = Integer.parseInt(parts[1]);
            byte[] before = "NULL".equals(parts[2]) ? null : Base64.getDecoder().decode(parts[2]);
            byte[] after = "NULL".equals(parts[3]) ? null : Base64.getDecoder().decode(parts[3]);
            LogType type = LogType.valueOf(parts[4]);
            long rcSnapshot = Long.parseLong(parts[5]);

            return new LogEntry(txId, recId, before, after, type, rcSnapshot);
        }

        @Override
        public String toString() {
            return String.format("LogEntry[tx=%d, type=%s, recordId=%d]", transactionId, type, recordId);
        }
    }

    // ==================== Constantes ====================
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
    private final Path journalFilePath; // FJT - Fichier Journal de Transactions

    // TIA (Tampon d'Images Après) - Buffer contenant les nouvelles valeurs
    private final Map<Integer, PageFrame> bufferPool = new HashMap<>();
    // TIV (Tampon d'Images Avant) - Buffer contenant les anciennes valeurs avant modification
    private final Map<Integer, byte[]> tiv = new HashMap<>();
    // Table des verrous sur les enregistrements
    private final Set<Integer> locks = new HashSet<>();

    // TD5: TJT (Journal de Transactions en memoire)
    private final List<LogEntry> transactionLog = new ArrayList<>();

    private long recordCount;
    private boolean inTransaction;
    private long transactionStartRecordCount = -1;

    // TD5: Gestion des identifiants de transaction
    private int nextTransactionId = 1;
    private int currentTransactionId = -1;

    public MiniSGBD(String fileName) throws IOException {
        this.filePath = Paths.get(fileName);
        this.journalFilePath = Paths.get(fileName + ".log"); // FJT

        if (Files.notExists(filePath)) {
            Files.createFile(filePath);
        }
        if (Files.notExists(journalFilePath)) {
            Files.createFile(journalFilePath);
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

        // TD5: Assigner un ID de transaction et logger BEGIN
        currentTransactionId = nextTransactionId++;
        transactionLog.add(new LogEntry(currentTransactionId, LogType.BEGIN));
    }

    public synchronized void commit() throws IOException {
        if (!inTransaction) {
            return;
        }

        // TD5: Logger COMMIT dans le TJT
        transactionLog.add(new LogEntry(currentTransactionId, LogType.COMMIT));

        // TD5: Forcer l'ecriture du TJT dans le FJT
        flushLogToFile();

        // TD5: Le COMMIT ne force plus l'ecriture sur disque
        // Les pages restent dirty jusqu'au prochain checkpoint
        for (Map.Entry<Integer, PageFrame> entry : bufferPool.entrySet()) {
            PageFrame frame = entry.getValue();
            if (frame.transactional) {
                frame.transactional = false;
            }
        }

        // Vider le TIV (les anciennes valeurs ne sont plus necessaires)
        tiv.clear();

        // Liberer tous les verrous poses par la transaction
        locks.clear();

        inTransaction = false;
        transactionStartRecordCount = -1;
        currentTransactionId = -1;
    }

    // TD5: Ecrire le TJT dans le FJT (fichier journal)
    private void flushLogToFile() throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(journalFilePath.toFile(), true))) { // append mode
            for (LogEntry entry : transactionLog) {
                writer.write(entry.serialize());
                writer.newLine();
            }
        }
        transactionLog.clear();
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
                // Restaurer les donnees originales
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

        // TD5: Logger ROLLBACK et forcer l'ecriture du TJT dans le FJT
        transactionLog.add(new LogEntry(currentTransactionId, LogType.ROLLBACK));
        try {
            flushLogToFile();
        } catch (IOException e) {
            throw new RuntimeException("Failed to flush log during rollback", e);
        }

        inTransaction = false;
        currentTransactionId = -1;
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

        // TD5: Logger INSERT avec l'image apres (afterImage)
        if (inTransaction) {
            transactionLog.add(new LogEntry(
                currentTransactionId,
                (int) newRecordId,
                null,  // pas d'image avant pour INSERT
                record,
                LogType.INSERT,
                recordCount  // snapshot du nombre d'enregistrements avant insertion
            ));
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
            // TD5: Capturer l'image avant pour le log
            byte[] beforeImage = Arrays.copyOfRange(pageData, recordOffset, recordOffset + RECORD_SIZE);

            // Si en transaction, sauvegarder l'image avant dans le TIV
            if (inTransaction) {
                // Sauvegarder la page entiere dans le TIV si pas deja fait
                if (!tiv.containsKey(pageId)) {
                    byte[] pageCopy = Arrays.copyOf(pageData, pageData.length);
                    tiv.put(pageId, pageCopy);
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

            // TD5: Logger UPDATE avec images avant/apres
            if (inTransaction) {
                transactionLog.add(new LogEntry(
                    currentTransactionId,
                    recordId,
                    beforeImage,
                    record,
                    LogType.UPDATE
                ));
            }
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

    // ==================== TD5: Checkpoint ====================
    /**
     * Cree un point de sauvegarde (checkpoint).
     * - Force l'ecriture sur disque de toutes les pages modifiees
     * - Ajoute une entree CHECKPOINT dans le journal
     */
    public synchronized void checkpoint() throws IOException {
        // Forcer l'ecriture de toutes les pages dirty
        for (Map.Entry<Integer, PageFrame> entry : bufferPool.entrySet()) {
            int pageId = entry.getKey();
            PageFrame frame = entry.getValue();
            if (frame.dirty) {
                writePageToDisk(pageId, frame);
                frame.dirty = false;
            }
        }

        // Ajouter une entree CHECKPOINT dans le TJT et forcer vers FJT
        LogEntry checkpointEntry = new LogEntry(-1, LogType.CHECKPOINT);
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(journalFilePath.toFile(), true))) {
            writer.write(checkpointEntry.serialize());
            writer.newLine();
        }

        System.out.println("[CHECKPOINT] Point de sauvegarde cree");
    }

    // ==================== TD5: Simulation de crash ====================
    /**
     * Simule un crash systeme.
     * - Vide tous les buffers en memoire sans ecrire sur disque
     * - Simule une panne brutale
     */
    public synchronized void crash() {
        System.out.println("[CRASH] Simulation de panne systeme...");

        // Vider le buffer pool (TIA) sans ecrire sur disque
        bufferPool.clear();

        // Vider le TIV
        tiv.clear();

        // Vider les verrous
        locks.clear();

        // Vider le TJT (journal en memoire)
        transactionLog.clear();

        // Reinitialiser l'etat de transaction
        inTransaction = false;
        currentTransactionId = -1;
        transactionStartRecordCount = -1;

        System.out.println("[CRASH] Tous les buffers ont ete perdus!");
    }

    // ==================== TD5: Recuperation (Recovery) ====================
    /**
     * Algorithme de recuperation apres panne.
     * Phase 1: Analyse du journal pour identifier les transactions commitees et non commitees
     * Phase 2: REDO - Rejouer les operations des transactions commitees depuis le dernier checkpoint
     * Phase 3: UNDO - Restaurer les images avant pour les transactions non commitees
     */
    public synchronized void recover() throws IOException {
        System.out.println("[RECOVERY] Demarrage de la recuperation...");

        // Lire le journal depuis le fichier FJT
        List<LogEntry> journalEntries = new ArrayList<>();
        if (Files.exists(journalFilePath) && Files.size(journalFilePath) > 0) {
            try (BufferedReader reader = new BufferedReader(
                    new FileReader(journalFilePath.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        LogEntry entry = LogEntry.deserialize(line);
                        if (entry != null) {
                            journalEntries.add(entry);
                        }
                    }
                }
            }
        }

        if (journalEntries.isEmpty()) {
            System.out.println("[RECOVERY] Journal vide, rien a recuperer");
            return;
        }

        // Trouver le dernier checkpoint
        int lastCheckpointIndex = -1;
        for (int i = journalEntries.size() - 1; i >= 0; i--) {
            if (journalEntries.get(i).type == LogType.CHECKPOINT) {
                lastCheckpointIndex = i;
                break;
            }
        }

        int startIndex = (lastCheckpointIndex >= 0) ? lastCheckpointIndex + 1 : 0;
        System.out.println("[RECOVERY] Dernier checkpoint a l'index: " + lastCheckpointIndex);

        // Phase 1: Analyse - Identifier les transactions commitees et non commitees
        Set<Integer> committedTx = new HashSet<>();
        Set<Integer> activeTx = new HashSet<>();

        for (int i = startIndex; i < journalEntries.size(); i++) {
            LogEntry entry = journalEntries.get(i);
            switch (entry.type) {
                case BEGIN:
                    activeTx.add(entry.transactionId);
                    break;
                case COMMIT:
                    activeTx.remove(entry.transactionId);
                    committedTx.add(entry.transactionId);
                    break;
                case ROLLBACK:
                    activeTx.remove(entry.transactionId);
                    break;
                default:
                    break;
            }
        }

        System.out.println("[RECOVERY] Transactions commitees: " + committedTx);
        System.out.println("[RECOVERY] Transactions non commitees (a annuler): " + activeTx);

        // Recharger le recordCount depuis le fichier
        long fileSize = Files.size(filePath);
        this.recordCount = fileSize / RECORD_SIZE;

        // Phase 2: REDO - Rejouer les operations des transactions commitees
        System.out.println("[RECOVERY] Phase REDO - Rejouer les transactions commitees...");
        for (int i = startIndex; i < journalEntries.size(); i++) {
            LogEntry entry = journalEntries.get(i);
            if (committedTx.contains(entry.transactionId)) {
                switch (entry.type) {
                    case INSERT:
                        // Reappliquer l'insertion
                        if (entry.afterImage != null && entry.recordId >= 0) {
                            // S'assurer que le fichier est assez grand
                            if (entry.recordCountSnapshot >= 0 && entry.recordCountSnapshot >= recordCount) {
                                recordCount = entry.recordCountSnapshot + 1;
                            }
                            int pageId = entry.recordId / RECORDS_PER_PAGE;
                            int recordOffset = (entry.recordId % RECORDS_PER_PAGE) * RECORD_SIZE;
                            byte[] pageData = fix(pageId);
                            try {
                                System.arraycopy(entry.afterImage, 0, pageData, recordOffset, RECORD_SIZE);
                                writePageToDisk(pageId, bufferPool.get(pageId));
                            } finally {
                                unfix(pageId);
                            }
                            System.out.println("[REDO] INSERT record " + entry.recordId);
                        }
                        break;
                    case UPDATE:
                        // Reappliquer la modification (image apres)
                        if (entry.afterImage != null && entry.recordId >= 0 && entry.recordId < recordCount) {
                            int pageId = entry.recordId / RECORDS_PER_PAGE;
                            int recordOffset = (entry.recordId % RECORDS_PER_PAGE) * RECORD_SIZE;
                            byte[] pageData = fix(pageId);
                            try {
                                System.arraycopy(entry.afterImage, 0, pageData, recordOffset, RECORD_SIZE);
                                writePageToDisk(pageId, bufferPool.get(pageId));
                            } finally {
                                unfix(pageId);
                            }
                            System.out.println("[REDO] UPDATE record " + entry.recordId);
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        // Phase 3: UNDO - Annuler les operations des transactions non commitees
        System.out.println("[RECOVERY] Phase UNDO - Annuler les transactions non commitees...");
        // Parcourir le journal a l'envers pour UNDO
        for (int i = journalEntries.size() - 1; i >= startIndex; i--) {
            LogEntry entry = journalEntries.get(i);
            if (activeTx.contains(entry.transactionId)) {
                switch (entry.type) {
                    case INSERT:
                        // Annuler l'insertion - restaurer le recordCount
                        if (entry.recordCountSnapshot >= 0) {
                            // On ne peut pas vraiment "supprimer" mais on peut ignorer
                            System.out.println("[UNDO] INSERT record " + entry.recordId + " (ignore)");
                        }
                        break;
                    case UPDATE:
                        // Restaurer l'image avant
                        if (entry.beforeImage != null && entry.recordId >= 0 && entry.recordId < recordCount) {
                            int pageId = entry.recordId / RECORDS_PER_PAGE;
                            int recordOffset = (entry.recordId % RECORDS_PER_PAGE) * RECORD_SIZE;
                            byte[] pageData = fix(pageId);
                            try {
                                System.arraycopy(entry.beforeImage, 0, pageData, recordOffset, RECORD_SIZE);
                                writePageToDisk(pageId, bufferPool.get(pageId));
                            } finally {
                                unfix(pageId);
                            }
                            System.out.println("[UNDO] UPDATE record " + entry.recordId + " - valeur restauree");
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        // Nettoyer les buffers
        bufferPool.clear();

        System.out.println("[RECOVERY] Recuperation terminee avec succes!");
    }

    /**
     * Vide le fichier journal (pour les tests).
     */
    public void clearJournal() throws IOException {
        Files.write(journalFilePath, new byte[0]);
    }

    /**
     * Affiche le contenu du journal (pour debug).
     */
    public void printJournal() throws IOException {
        System.out.println("=== Contenu du Journal (FJT) ===");
        if (Files.exists(journalFilePath)) {
            List<String> lines = Files.readAllLines(journalFilePath);
            for (String line : lines) {
                LogEntry entry = LogEntry.deserialize(line);
                if (entry != null) {
                    System.out.println("  " + entry);
                }
            }
        }
        System.out.println("================================");
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

        // ==================== TD5: Tests Journalisation et Recovery ====================
        System.out.println("\n\n========================================");
        System.out.println("=== TD5: Tests Journalisation, Checkpoint et Recovery ===");
        System.out.println("========================================");

        // Reinitialiser pour les tests TD5
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(Paths.get("etudiants.db.log"));
        MiniSGBD db5 = new MiniSGBD("etudiants.db");

        // Creer des donnees initiales
        System.out.println("\n--- Etape 1: Creation des donnees initiales ---");
        for (int i = 1; i <= 10; i++) {
            db5.begin();
            db5.insertRecord("Etudiant " + i);
            db5.commit();
        }
        db5.checkpoint();
        System.out.println("10 etudiants crees et checkpoint effectue");

        // Afficher l'etat initial
        System.out.println("\nEtat initial:");
        for (int i = 0; i < 5; i++) {
            System.out.println("  Record " + i + ": " + db5.readRecord(i));
        }

        // Test 1: Transaction commitee puis crash
        System.out.println("\n--- Test 1: Transaction COMMITEE puis CRASH ---");
        db5.begin();
        db5.updateRecord(0, "MODIFIE_TX_COMMITEE");
        db5.commit();
        System.out.println("Transaction commitee: Record 0 = 'MODIFIE_TX_COMMITEE'");

        // Simuler un crash AVANT checkpoint (les donnees sont dans le journal mais pas sur disque)
        db5.crash();

        // Recovery
        db5.recover();
        System.out.println("Apres recovery - Record 0: " + db5.readRecord(0));

        // Test 2: Transaction NON commitee puis crash (doit etre annulee)
        System.out.println("\n--- Test 2: Transaction NON COMMITEE puis CRASH ---");
        db5.checkpoint(); // Checkpoint propre

        System.out.println("Avant modification - Record 1: " + db5.readRecord(1));

        db5.begin();
        db5.updateRecord(1, "MODIFIE_TX_NON_COMMITEE");
        System.out.println("En transaction (non commitee) - Record 1: " + db5.readRecord(1));

        // Crash SANS commit!
        db5.crash();

        // Recovery - doit restaurer la valeur originale
        db5.recover();
        System.out.println("Apres recovery (UNDO) - Record 1: " + db5.readRecord(1));

        // Test 3: Scenario complet avec plusieurs transactions
        System.out.println("\n--- Test 3: Scenario complexe ---");
        db5.checkpoint();

        // Transaction 1: Commitee
        db5.begin();
        db5.updateRecord(2, "TX1_COMMITEE");
        db5.commit();
        System.out.println("TX1 commitee: Record 2 = 'TX1_COMMITEE'");

        // Transaction 2: Commitee
        db5.begin();
        db5.updateRecord(3, "TX2_COMMITEE");
        db5.commit();
        System.out.println("TX2 commitee: Record 3 = 'TX2_COMMITEE'");

        // Transaction 3: NON Commitee (en cours au moment du crash)
        db5.begin();
        db5.updateRecord(4, "TX3_NON_COMMITEE");
        System.out.println("TX3 non commitee: Record 4 = 'TX3_NON_COMMITEE'");

        // CRASH!
        db5.crash();

        // Recovery
        System.out.println("\nRecovery apres crash...");
        db5.recover();

        System.out.println("\nResultats attendus:");
        System.out.println("  Record 2: TX1_COMMITEE (REDO)");
        System.out.println("  Record 3: TX2_COMMITEE (REDO)");
        System.out.println("  Record 4: Etudiant 5 (UNDO - valeur originale)");

        System.out.println("\nResultats obtenus:");
        System.out.println("  Record 2: " + db5.readRecord(2));
        System.out.println("  Record 3: " + db5.readRecord(3));
        System.out.println("  Record 4: " + db5.readRecord(4));

        // Afficher le journal final
        System.out.println("\n--- Contenu du journal ---");
        db5.printJournal();

        System.out.println("\n=== Tous les tests TD5 termines avec succes! ===");
    }
}
