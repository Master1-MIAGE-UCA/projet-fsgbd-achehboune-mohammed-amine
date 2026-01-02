# Mini-SGBD en Java

## Projet de Gestion de Base de Donnees Simplifie

Ce projet implemente un mini systeme de gestion de base de donnees (SGBD) en Java, couvrant les concepts fondamentaux des SGBD relationnels : stockage par pages, gestion de buffer, transactions, verrouillage et reprise sur panne.

---

## Structure du Projet

```
projet-fsgbd-achehboune-mohammed-amine/
├── src/
│   └── MiniSGBD.java      # Code source principal (toutes les etapes)
├── etudiants.db           # Fichier de donnees (genere a l'execution)
├── etudiants.db.log       # Journal de transactions (TD5)
├── airbase.sql            # Script SQL pour exercices Oracle
├── td4_interpretation.md  # Exercices concurrence Oracle
└── README.md              # Ce fichier
```

---

## Etape 1 : Stockage des donnees

### Objectif
Comprendre comment un SGBD organise ses donnees sur disque en **pages de taille fixe**.

### Concepts cles

| Concept | Valeur | Description |
|---------|--------|-------------|
| `PAGE_SIZE` | 4096 octets (4 Ko) | Taille d'une page |
| `RECORD_SIZE` | 100 octets | Taille d'un enregistrement |
| `RECORDS_PER_PAGE` | 40 | Nombre d'enregistrements par page |

### Organisation des donnees
```
Page 0 : Enregistrements 0 → 39
Page 1 : Enregistrements 40 → 79
Page 2 : Enregistrements 80 → 119
...
```

### Methodes implementees

| Methode | Description | Ligne |
|---------|-------------|-------|
| `insertRecord(String data)` | Insere un enregistrement (padding a 100 octets) | ~382 |
| `readRecord(int recordId)` | Lit un enregistrement par son ID | ~469 |
| `getPage(int pageNumber)` | Retourne tous les enregistrements d'une page | ~499 |

### A retenir
- Les donnees sont stockees en **pages** de taille fixe, pas ligne par ligne
- Chaque enregistrement est **normalise a 100 octets** (padding avec zeros)
- L'ID d'un enregistrement determine sa page : `pageId = recordId / RECORDS_PER_PAGE`

### Tests (dans main())
```java
// Lignes 783-815
for (int i = 1; i <= 105; i++) {
    db.insertRecord("Etudiant " + i);
}
System.out.println("Enregistrement 42 : " + db.readRecord(41));
System.out.println("Page 1 : " + db.getPage(0));
```

---

## Etape 2 : Gestion du Buffer (FIX / UNFIX / USE / FORCE)

### Objectif
Introduire un **buffer en memoire** pour eviter les acces disque repetitifs.

### Primitives implementees

| Primitive | Description | Ligne |
|-----------|-------------|-------|
| `fix(int pageId)` | Charge une page en memoire, incremente pinCount | ~254 |
| `unfix(int pageId)` | Decremente pinCount (page plus utilisee) | ~267 |
| `use(int pageId)` | Marque la page comme modifiee (dirty) | ~275 |
| `force(int pageId)` | Ecrit la page sur disque si dirty | ~286 |

### Structure PageFrame
```java
private static final class PageFrame {
    byte[] data;        // Contenu de la page
    int pinCount;       // Compteur d'utilisation
    boolean dirty;      // Page modifiee?
    boolean transactional; // Liee a une transaction?
}
```

### Insertion synchrone
```java
insertRecordSync(String data)  // Ecrit dans le buffer ET sur disque
insertRecord(String data)       // Ecrit uniquement dans le buffer
```

### A retenir
- Le **buffer pool** (`Map<Integer, PageFrame>`) stocke les pages en memoire
- `FIX` avant lecture, `UNFIX` apres utilisation
- `USE` marque dirty, `FORCE` ecrit sur disque
- Evite les I/O disque couteuses

### Tests
Les tests TD1 utilisent implicitement ces primitives via `readRecord` et `getPage`.

---

## Etape 3 : Transactions (BEGIN / COMMIT / ROLLBACK)

### Objectif
Implementer des **transactions** : ensemble d'operations atomiques.

### Methodes implementees

| Methode | Description | Ligne |
|---------|-------------|-------|
| `begin()` | Demarre une transaction | ~144 |
| `commit()` | Valide et persiste les modifications | ~160 |
| `rollback()` | Annule toutes les modifications | ~203 |

### Comportement

```
BEGIN     → inTransaction = true
COMMIT    → Ecrit les pages dirty sur disque, termine la transaction
ROLLBACK  → Ignore les modifications, termine la transaction
```

### Indicateur transactionnel
Chaque page a un flag `transactional` qui indique si elle appartient a la transaction en cours.

### A retenir
- Une transaction garantit l'**atomicite** (tout ou rien)
- `COMMIT` persiste les changements
- `ROLLBACK` les annule
- Si `begin()` est appele pendant une transaction, l'ancienne est commitee

### Tests (dans main())
```java
// Lignes 817-829
db.begin();
db.insertRecord("Etudiant 200");
db.insertRecord("Etudiant 201");
db.rollback();  // → Aucune modification
System.out.println("Count apres rollback (attendu 105) : " + db.getRecordCount());

db.begin();
db.insertRecord("Etudiant 202");
db.commit();    // → Modifications persistees
```

---

## Etape 4 : TIV, TIA et Verrouillage

### Objectif
Ajouter la gestion des **images avant/apres** et le **verrouillage** des enregistrements.

### Concepts cles

| Buffer | Description |
|--------|-------------|
| **TIA** (Tampon Images Apres) | Buffer actuel avec les nouvelles valeurs |
| **TIV** (Tampon Images Avant) | Anciennes valeurs avant modification |

### Structures ajoutees

```java
Map<Integer, byte[]> tiv = new HashMap<>();  // Images avant (par page)
Set<Integer> locks = new HashSet<>();         // Verrous (par recordId)
```

### Nouvelle methode

| Methode | Description | Ligne |
|---------|-------------|-------|
| `updateRecord(int recordId, String newData)` | Met a jour avec verrouillage | ~398 |
| `isLocked(int recordId)` | Verifie si un enregistrement est verrouille | ~458 |

### Logique de mise a jour
```
1. Verifier si l'enregistrement est verrouille → Exception si oui
2. Sauvegarder la page dans le TIV (si pas deja fait)
3. Poser un verrou sur l'enregistrement
4. Modifier dans le TIA uniquement
5. Marquer dirty + transactional
```

### Politique de lecture

| Situation | Source |
|-----------|--------|
| Pas de transaction | TIA |
| Transaction + pas de verrou | TIA |
| Transaction + verrou | **TIV** (ancienne valeur) |

### A retenir
- Le **TIV** permet le rollback sans journal disque
- Les **verrous** empechent les modifications concurrentes
- Lecture coherente : retourne l'ancienne valeur si l'enregistrement est verrouille

### Tests (dans main())
```java
// Lignes 834-882 - Tests TIV/TIA/Verrouillage
// Test 1: UPDATE avec ROLLBACK
db.begin();
db.updateRecord(0, "Etudiant MODIFIE");
db.rollback();  // → Valeur originale restauree

// Test 2: UPDATE avec COMMIT
db.begin();
db.updateRecord(1, "Etudiant 2 MODIFIE PERMANENT");
db.commit();    // → Nouvelle valeur persistee

// Test 5: Detection de verrouillage
db.begin();
db.updateRecord(20, "Premier UPDATE");
db.updateRecord(20, "Deuxieme UPDATE");  // → IllegalStateException!
```

---

## Etape 5 : Journalisation et Recovery (UNDO/REDO)

### Objectif
Implementer la **reprise sur panne** avec journalisation et algorithme UNDO/REDO.

### Nouvelles structures

#### LogEntry (classe interne)
```java
class LogEntry {
    int transactionId;      // ID de la transaction
    int recordId;           // ID de l'enregistrement (-1 si N/A)
    byte[] beforeImage;     // Ancienne valeur
    byte[] afterImage;      // Nouvelle valeur
    LogType type;           // BEGIN, UPDATE, INSERT, COMMIT, ROLLBACK, CHECKPOINT
    long recordCountSnapshot; // Pour INSERT
}
```

#### Journaux
```java
List<LogEntry> transactionLog;  // TJT (en memoire)
Path journalFilePath;            // FJT (fichier .log)
```

### Methodes implementees

| Methode | Description | Ligne |
|---------|-------------|-------|
| `checkpoint()` | Force les pages dirty + ecrit CHECKPOINT | ~542 |
| `crash()` | Simule une panne (vide les buffers) | ~570 |
| `recover()` | Algorithme de recuperation REDO/UNDO | ~600 |
| `flushLogToFile()` | Ecrit le TJT dans le FJT | ~191 |
| `printJournal()` | Affiche le contenu du journal | ~763 |

### Algorithme de Recovery

```
1. ANALYSE
   - Lire le journal (FJT)
   - Trouver le dernier CHECKPOINT
   - Identifier transactions commitees vs non-commitees

2. REDO (transactions commitees)
   - Reappliquer les afterImage
   - Ecrire sur disque

3. UNDO (transactions non-commitees)
   - Restaurer les beforeImage
   - Annuler les modifications
```

### Modifications des operations

| Operation | Ajout TD5 |
|-----------|-----------|
| `begin()` | Log BEGIN + ID transaction |
| `insertRecord()` | Log INSERT avec afterImage |
| `updateRecord()` | Log UPDATE avec beforeImage/afterImage |
| `commit()` | Log COMMIT, flush FJT, **NE force plus sur disque** |
| `rollback()` | Log ROLLBACK, flush FJT |

### A retenir
- Le **journal** garantit la durabilite (D de ACID)
- **Checkpoint** reduit le temps de recovery
- **REDO** : rejouer les transactions commitees
- **UNDO** : annuler les transactions non-commitees
- Le COMMIT n'ecrit plus directement sur disque (c'est le checkpoint qui le fait)

### Tests (dans main())
```java
// Lignes 886-985 - Tests Recovery

// Test 1: Transaction COMMITEE puis CRASH
db5.begin();
db5.updateRecord(0, "MODIFIE_TX_COMMITEE");
db5.commit();
db5.crash();    // Perte des buffers
db5.recover();  // REDO → valeur restauree

// Test 2: Transaction NON COMMITEE puis CRASH
db5.begin();
db5.updateRecord(1, "MODIFIE_TX_NON_COMMITEE");
// PAS de commit!
db5.crash();
db5.recover();  // UNDO → valeur originale

// Test 3: Scenario complexe
// TX1, TX2 commitees + TX3 non commitee
// → REDO TX1, TX2 / UNDO TX3
```

---

## Execution des Tests

### Compilation
```bash
cd src
javac MiniSGBD.java
```

### Execution
```bash
java MiniSGBD
```

### Sortie attendue
```
=== TD1-TD3: Tests de base ===
Enregistrement 42 : Etudiant 42
Page 1 : [Etudiant 1, Etudiant 2, ...]
...

=== TD4: Tests TIV, TIA et Verrouillage ===
--- Test 1: UPDATE avec ROLLBACK ---
...

=== TD5: Tests Journalisation, Checkpoint et Recovery ===
[CHECKPOINT] Point de sauvegarde cree
[CRASH] Simulation de panne systeme...
[RECOVERY] Demarrage de la recuperation...
[REDO] UPDATE record 0
...
=== Tous les tests TD5 termines avec succes! ===
```

---

## Resume des Concepts SGBD

| Etape | Concept | Implementation |
|-------|---------|----------------|
| TD1 | Stockage par pages | `PAGE_SIZE`, `RECORD_SIZE`, pages de 40 enregistrements |
| TD2 | Buffer management | `FIX`, `UNFIX`, `USE`, `FORCE`, `PageFrame` |
| TD3 | Transactions | `BEGIN`, `COMMIT`, `ROLLBACK`, flag `transactional` |
| TD4 | Images avant/apres | `TIV`, `TIA`, `locks`, `updateRecord` |
| TD5 | Journalisation | `LogEntry`, `checkpoint`, `crash`, `recover` |

---

## Fichiers generes

| Fichier | Description |
|---------|-------------|
| `etudiants.db` | Fichier de donnees (enregistrements) |
| `etudiants.db.log` | Journal de transactions (FJT) |

---

## Auteur

Projet realise dans le cadre du cours de Gestion de Fichiers et Structures de Donnees (FSGBD).
