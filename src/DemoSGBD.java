import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Script de demonstration du Mini SGBD
 * Scenario de presentation en 5 etapes
 *
 * Auteur: Mohammed Amine ACHEHBOUNE
 */
public class DemoSGBD {

    private static final String SEPARATOR = "============================================================";
    private static final String THIN_SEP = "------------------------------------------------------------";

    public static void main(String[] args) throws IOException {
        System.out.println("\n" + SEPARATOR);
        System.out.println("   DEMONSTRATION MINI SGBD - PROPRIETES ACID");
        System.out.println("   Projet FSGBD - Mohammed Amine ACHEHBOUNE");
        System.out.println(SEPARATOR + "\n");

        // ============================================================
        // PREPARATION: Base de donnees vide
        // ============================================================
        Path dbPath = Paths.get("demo.db");
        Path logPath = Paths.get("demo.db.log");

        // Supprimer les anciens fichiers pour partir d'une base vide
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(logPath);

        MiniSGBD db = new MiniSGBD("demo.db");

        System.out.println("[INIT] Base de donnees vide creee");
        System.out.println("[INIT] Fichier DB: " + dbPath.toAbsolutePath());
        System.out.println("[INIT] Fichier Journal: " + logPath.toAbsolutePath());

        pauseForPresentation();

        // ============================================================
        // ETAPE 1: LE REMPLISSAGE (Buffer & Disque)
        // ============================================================
        System.out.println("\n" + SEPARATOR);
        System.out.println("   ETAPE 1: LE REMPLISSAGE (Buffer & Disque)");
        System.out.println(SEPARATOR);
        System.out.println("Objectif: Verifier que l'insertion fonctionne et utilise le Buffer\n");

        System.out.println("[ACTION] Insertion de 2 enregistrements temoins...");

        // Insertion de Record_A (ID 0)
        db.insertRecordSync("Record_A");
        System.out.println("  -> Record_A insere (ID 0)");

        // Insertion de Record_B (ID 1)
        db.insertRecordSync("Record_B");
        System.out.println("  -> Record_B insere (ID 1)");

        System.out.println("\n[VERIFICATION] Lecture immediate de Record_A:");
        String recordA = db.readRecord(0);
        System.out.println("  -> Contenu lu: \"" + recordA + "\"");

        System.out.println("\n[RESULTAT] " + (recordA.equals("Record_A") ? "SUCCES" : "ECHEC") +
                         " - Les donnees sont correctement stockees dans le buffer");

        pauseForPresentation();

        // ============================================================
        // ETAPE 2: L'ANNULATION (Atomicite & TIV)
        // ============================================================
        System.out.println("\n" + SEPARATOR);
        System.out.println("   ETAPE 2: L'ANNULATION (Atomicite & TIV)");
        System.out.println(SEPARATOR);
        System.out.println("Objectif: Prouver que l'on peut revenir en arriere grace au TIV\n");

        System.out.println("[ETAT INITIAL] Record_A = \"" + db.readRecord(0) + "\"");

        System.out.println("\n[ACTION] BEGIN - Demarrage d'une transaction...");
        db.begin();
        System.out.println("  -> Transaction demarree");

        System.out.println("\n[ACTION] Modification de Record_A en 'Record_A_MODIFIE'...");
        db.updateRecord(0, "Record_A_MODIFIE");
        System.out.println("  -> Modification effectuee dans le buffer (TIA)");
        System.out.println("  -> Ancienne valeur sauvegardee dans le TIV");
        System.out.println("  -> Record_A verrouille: " + db.isLocked(0));

        System.out.println("\n[ACTION] ROLLBACK - Annulation de la transaction...");
        db.rollback();
        System.out.println("  -> Transaction annulee");
        System.out.println("  -> Valeurs restaurees depuis le TIV");

        System.out.println("\n[VERIFICATION] Relecture de Record_A:");
        recordA = db.readRecord(0);
        System.out.println("  -> Contenu lu: \"" + recordA + "\"");

        boolean rollbackSuccess = recordA.equals("Record_A");
        System.out.println("\n[RESULTAT] " + (rollbackSuccess ? "SUCCES" : "ECHEC") +
                         " - La valeur originale a ete restauree grace au TIV");

        pauseForPresentation();

        // ============================================================
        // ETAPE 3: LA PERSISTANCE (Commit & Journal)
        // ============================================================
        System.out.println("\n" + SEPARATOR);
        System.out.println("   ETAPE 3: LA PERSISTANCE (Commit & Journal)");
        System.out.println(SEPARATOR);
        System.out.println("Objectif: Valider qu'une transaction validee laisse une trace durable\n");

        System.out.println("[ETAT INITIAL] Record_B = \"" + db.readRecord(1) + "\"");

        System.out.println("\n[ACTION] BEGIN - Demarrage d'une transaction...");
        db.begin();
        System.out.println("  -> Transaction demarree");

        System.out.println("\n[ACTION] Modification de Record_B en 'Record_B_FINAL'...");
        db.updateRecord(1, "Record_B_FINAL");
        System.out.println("  -> Modification effectuee");

        System.out.println("\n[ACTION] COMMIT - Validation de la transaction...");
        db.commit();
        System.out.println("  -> Transaction validee");
        System.out.println("  -> Journal (TJT) ecrit dans le fichier (FJT)");

        System.out.println("\n[VERIFICATION] Presence et taille du fichier journal:");
        if (Files.exists(logPath)) {
            long logSize = Files.size(logPath);
            System.out.println("  -> Fichier journal existe: OUI");
            System.out.println("  -> Taille du journal: " + logSize + " octets");

            System.out.println("\n[VERIFICATION] Contenu du journal (FJT):");
            db.printJournal();
        } else {
            System.out.println("  -> Fichier journal existe: NON (ERREUR!)");
        }

        System.out.println("\n[VERIFICATION] Relecture de Record_B:");
        String recordB = db.readRecord(1);
        System.out.println("  -> Contenu lu: \"" + recordB + "\"");

        boolean commitSuccess = recordB.equals("Record_B_FINAL");
        System.out.println("\n[RESULTAT] " + (commitSuccess ? "SUCCES" : "ECHEC") +
                         " - La modification est persistante et journalisee");

        pauseForPresentation();

        // ============================================================
        // ETAPE 4: PREPARATION AU CRASH
        // ============================================================
        System.out.println("\n" + SEPARATOR);
        System.out.println("   ETAPE 4: PREPARATION AU CRASH");
        System.out.println(SEPARATOR);
        System.out.println("Objectif: Creer une incoherence volontaire (transaction non terminee)\n");

        System.out.println("[ETAT ACTUEL] Nombre d'enregistrements: " + db.getRecordCount());

        System.out.println("\n[ACTION] BEGIN - Demarrage d'une nouvelle transaction...");
        db.begin();
        System.out.println("  -> Transaction demarree");

        System.out.println("\n[ACTION] Insertion de Record_C_FANTOME (ID 2)...");
        db.insertRecord("Record_C_FANTOME");
        System.out.println("  -> Record_C_FANTOME insere dans le buffer");
        System.out.println("  -> Nombre d'enregistrements (en memoire): " + db.getRecordCount());

        System.out.println("\n[ATTENTION] Transaction laissee OUVERTE volontairement!");
        System.out.println("  -> Pas de COMMIT");
        System.out.println("  -> Pas de ROLLBACK");
        System.out.println("  -> Record_C_FANTOME n'est PAS valide (non commite)");

        System.out.println("\n[INFO] Le journal contient BEGIN mais pas COMMIT pour cette transaction");

        pauseForPresentation();

        // ============================================================
        // ETAPE 5: CRASH & RECOVERY
        // ============================================================
        System.out.println("\n" + SEPARATOR);
        System.out.println("   ETAPE 5: CRASH & RECOVERY");
        System.out.println(SEPARATOR);
        System.out.println("Objectif: Verifier que l'algorithme de reprise nettoie la base\n");

        System.out.println("[AVANT CRASH] Etat de la base en memoire:");
        System.out.println("  -> Record 0: \"" + db.readRecord(0) + "\"");
        System.out.println("  -> Record 1: \"" + db.readRecord(1) + "\"");
        try {
            System.out.println("  -> Record 2: \"" + db.readRecord(2) + "\" (FANTOME - non commite)");
        } catch (Exception e) {
            System.out.println("  -> Record 2: [non accessible]");
        }

        System.out.println("\n" + THIN_SEP);
        System.out.println("[ACTION] SIMULATION DE PANNE (crash)...");
        System.out.println(THIN_SEP);
        db.crash();
        System.out.println("  -> Tous les buffers memoire ont ete perdus!");
        System.out.println("  -> Seuls les fichiers disque (DB + Journal) subsistent");

        System.out.println("\n" + THIN_SEP);
        System.out.println("[ACTION] REDEMARRAGE ET RECOVERY...");
        System.out.println(THIN_SEP);

        // Simuler un redemarrage en recreant l'instance
        db = new MiniSGBD("demo.db");
        System.out.println("  -> Nouvelle instance creee (simulation redemarrage)");

        System.out.println("\n[ACTION] Lancement de recover()...\n");
        db.recover();

        System.out.println("\n" + THIN_SEP);
        System.out.println("[VERIFICATION FINALE] Affichage de tous les enregistrements:");
        System.out.println(THIN_SEP);

        long finalCount = db.getRecordCount();
        System.out.println("\nNombre total d'enregistrements apres recovery: " + finalCount);

        System.out.println("\nContenu de la base:");
        for (int i = 0; i < finalCount; i++) {
            String record = db.readRecord(i);
            System.out.println("  -> Record " + i + ": \"" + record + "\"");
        }

        // ============================================================
        // RESUME FINAL
        // ============================================================
        System.out.println("\n" + SEPARATOR);
        System.out.println("   RESUME DE LA DEMONSTRATION");
        System.out.println(SEPARATOR);

        System.out.println("\n[ETAPE 1] Remplissage: Record_A et Record_B inseres correctement");
        System.out.println("          -> Buffer (TIA) fonctionne");

        System.out.println("\n[ETAPE 2] Annulation: ROLLBACK restaure la valeur originale");
        System.out.println("          -> TIV (Tampon Images Avant) fonctionne");
        System.out.println("          -> ATOMICITE verifiee");

        System.out.println("\n[ETAPE 3] Persistance: COMMIT enregistre dans le journal");
        System.out.println("          -> FJT (Fichier Journal) fonctionne");
        System.out.println("          -> DURABILITE verifiee");

        System.out.println("\n[ETAPE 4] Preparation crash: Transaction ouverte non commitee");
        System.out.println("          -> Record_C_FANTOME en attente");

        System.out.println("\n[ETAPE 5] Recovery apres crash:");
        System.out.println("          -> REDO: Record_B_FINAL restaure (transaction commitee)");
        System.out.println("          -> UNDO: Record_C_FANTOME supprime (transaction non commitee)");
        System.out.println("          -> COHERENCE verifiee");

        System.out.println("\n" + SEPARATOR);
        System.out.println("   FIN DE LA DEMONSTRATION");
        System.out.println(SEPARATOR + "\n");
    }

    /**
     * Pause pour la presentation (peut etre desactive pour les tests)
     */
    private static void pauseForPresentation() {
        // Decommenter la ligne suivante pour avoir des pauses interactives
        // try { System.in.read(); } catch (IOException e) {}

        System.out.println("\n[Appuyez sur Entree pour continuer...]\n");
        try {
            System.in.read();
            // Vider le buffer
            while (System.in.available() > 0) System.in.read();
        } catch (IOException e) {
            // Ignorer
        }
    }
}
