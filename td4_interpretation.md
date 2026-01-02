# TD4 - Contrôle de Concurrence Oracle

## Exercice 1 : Mise en œuvre des anomalies de concurrence

### 1.1 Mises à jour perdues (LOST UPDATE)

**Scénario**: Deux transactions lisent et modifient le même salaire simultanément.

```sql
-- SESSION 1                              -- SESSION 2
SET TRANSACTION NAME 'TX1';               SET TRANSACTION NAME 'TX2';

-- Lire le salaire de Miranda
SELECT sal FROM pilote WHERE pl# = 1;
-- Résultat: 18009

                                          -- Lire le même salaire
                                          SELECT sal FROM pilote WHERE pl# = 1;
                                          -- Résultat: 18009

-- Augmenter de 1000
UPDATE pilote
SET sal = 18009 + 1000
WHERE pl# = 1;
-- sal = 19009

                                          -- Augmenter de 500 (basé sur ancienne valeur!)
                                          UPDATE pilote
                                          SET sal = 18009 + 500
                                          WHERE pl# = 1;
                                          -- BLOQUÉ en attente...

COMMIT;
                                          -- Maintenant exécuté: sal = 18509
                                          COMMIT;

-- RÉSULTAT FINAL: sal = 18509
-- L'augmentation de 1000 de TX1 est PERDUE!
```

---

### 1.2 Lectures impropres (DIRTY READ)

**Scénario**: Une transaction lit des données non committées qui seront annulées.

```sql
-- SESSION 1                              -- SESSION 2
SET TRANSACTION NAME 'TX1';

UPDATE pilote
SET sal = 50000
WHERE pl# = 1;
-- Modification non committée
                                          -- Avec isolation READ UNCOMMITTED (si supporté)
                                          SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED;

                                          SELECT sal FROM pilote WHERE pl# = 1;
                                          -- Lit 50000 (donnée "dirty")

                                          -- Décision basée sur ce salaire...
                                          UPDATE pilote SET sal = sal * 1.1
                                          WHERE pl# = 2;

ROLLBACK;
-- Le salaire revient à 18009
                                          COMMIT;
-- SESSION 2 a pris une décision basée sur une valeur qui n'a jamais existé!
```

**Note**: Oracle ne supporte pas READ UNCOMMITTED par défaut, mais on peut simuler avec des logs ou expliquer le concept.

---

### 1.3 Lectures non reproductibles (NON-REPEATABLE READ)

**Scénario**: Une transaction lit deux fois la même donnée et obtient des valeurs différentes.

```sql
-- SESSION 1                              -- SESSION 2
SET TRANSACTION ISOLATION LEVEL
READ COMMITTED;

SELECT sal FROM pilote WHERE pl# = 1;
-- Résultat: 18009

                                          UPDATE pilote
                                          SET sal = 25000
                                          WHERE pl# = 1;
                                          COMMIT;

-- Relire la même donnée
SELECT sal FROM pilote WHERE pl# = 1;
-- Résultat: 25000 (DIFFÉRENT!)

COMMIT;
-- La même requête donne des résultats différents dans la même transaction
```

---

### 1.4 Lignes fantômes (PHANTOM READ)

**Scénario**: De nouvelles lignes apparaissent entre deux lectures.

```sql
-- SESSION 1                              -- SESSION 2
SET TRANSACTION ISOLATION LEVEL
READ COMMITTED;

-- Compter les pilotes de Paris
SELECT COUNT(*) FROM pilote
WHERE adr = 'Paris';
-- Résultat: 6 pilotes

                                          -- Insérer un nouveau pilote à Paris
                                          INSERT INTO pilote VALUES
                                          (24, 'Nouveau', '01-JAN-1980',
                                           'Paris', '0123456789', 20000);
                                          COMMIT;

-- Recompter
SELECT COUNT(*) FROM pilote
WHERE adr = 'Paris';
-- Résultat: 7 pilotes (FANTÔME!)

COMMIT;
```

---

## Exercice 2 : Solutions aux anomalies

### 2.1 Solution par Verrous Explicites

```sql
-- LOST UPDATE - Solution avec SELECT FOR UPDATE
-- SESSION 1
SELECT sal FROM pilote WHERE pl# = 1 FOR UPDATE;
-- Verrouille la ligne, SESSION 2 doit attendre
UPDATE pilote SET sal = sal + 1000 WHERE pl# = 1;
COMMIT;

-- DIRTY READ - Solution avec verrou partagé
LOCK TABLE pilote IN SHARE MODE;
-- Empêche les modifications pendant la lecture
SELECT * FROM pilote;
COMMIT;

-- NON-REPEATABLE READ - Solution
SELECT * FROM pilote WHERE pl# = 1 FOR UPDATE;
-- Maintient le verrou jusqu'au COMMIT

-- PHANTOM READ - Solution avec verrou de table
LOCK TABLE pilote IN SHARE MODE;
SELECT COUNT(*) FROM pilote WHERE adr = 'Paris';
-- Aucune insertion possible
```

### 2.2 Solution par Niveaux d'Isolation

```sql
-- Pour DIRTY READ (Oracle l'évite par défaut)
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;

-- Pour NON-REPEATABLE READ et PHANTOM READ
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;

-- Exemple complet
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
SELECT sal FROM pilote WHERE pl# = 1;
-- ... autres opérations ...
SELECT sal FROM pilote WHERE pl# = 1;
-- Même résultat garanti!
COMMIT;
```

### Tableau récapitulatif des solutions

| Anomalie            | Verrou                       | Niveau d'Isolation |
|---------------------|------------------------------|--------------------|
| Lost Update         | `SELECT ... FOR UPDATE`      | SERIALIZABLE       |
| Dirty Read          | (Oracle l'évite par défaut)  | READ COMMITTED     |
| Non-repeatable Read | `SELECT ... FOR UPDATE`      | SERIALIZABLE       |
| Phantom Read        | `LOCK TABLE ... IN SHARE MODE` | SERIALIZABLE     |

---

## Exercice 3 : Deadlocks

### 3.1 Mise en œuvre d'un Deadlock

```sql
-- SESSION 1                              -- SESSION 2
SET TRANSACTION NAME 'TX1';               SET TRANSACTION NAME 'TX2';

-- Verrouiller le pilote 1
UPDATE pilote SET sal = sal + 100
WHERE pl# = 1;
                                          -- Verrouiller l'avion 1
                                          UPDATE avion SET cap = cap + 10
                                          WHERE av# = 1;

-- Essayer de verrouiller l'avion 1
-- (BLOQUÉ - TX2 le détient)
UPDATE avion SET cap = cap + 5
WHERE av# = 1;
                                          -- Essayer de verrouiller le pilote 1
                                          -- (BLOQUÉ - TX1 le détient)
                                          UPDATE pilote SET sal = sal + 200
                                          WHERE pl# = 1;

-- DEADLOCK DÉTECTÉ!
-- Oracle annule automatiquement une des transactions:
-- ORA-00060: deadlock detected while waiting for resource
```

### 3.2 Solutions au Deadlock

#### Solution 1 : Ordre d'acquisition des verrous

Toujours acquérir les verrous dans le même ordre (par table, puis par clé).
Convention: AVION avant PILOTE, par ordre croissant de clé.

```sql
-- SESSION 1                              -- SESSION 2
-- D'abord AVION                          -- D'abord AVION
SELECT * FROM avion                       SELECT * FROM avion
WHERE av# = 1 FOR UPDATE;                 WHERE av# = 1 FOR UPDATE;
                                          -- Bloqué, attend TX1

SELECT * FROM pilote
WHERE pl# = 1 FOR UPDATE;
-- Modifications...
COMMIT;
                                          -- Maintenant peut continuer
                                          SELECT * FROM pilote
                                          WHERE pl# = 1 FOR UPDATE;
                                          COMMIT;
```

#### Solution 2 : Timeout avec NOWAIT ou WAIT

```sql
-- Utiliser NOWAIT pour échouer immédiatement si verrou impossible
SELECT * FROM pilote WHERE pl# = 1 FOR UPDATE NOWAIT;
-- ORA-00054: resource busy si déjà verrouillé

-- Ou utiliser WAIT avec timeout (en secondes)
SELECT * FROM pilote WHERE pl# = 1 FOR UPDATE WAIT 5;
-- Attend max 5 secondes puis échoue

-- Gestion avec exception
BEGIN
    SELECT * FROM pilote WHERE pl# = 1 FOR UPDATE NOWAIT;
    UPDATE pilote SET sal = sal + 100 WHERE pl# = 1;
    COMMIT;
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -54 THEN
            DBMS_OUTPUT.PUT_LINE('Ressource occupée, réessayer plus tard');
            ROLLBACK;
        ELSE
            RAISE;
        END IF;
END;
/
```

---

## Exercice 4 : Techniques de verrouillage Oracle

### 4.1 Modes de verrouillage de table

```sql
-- Mode ROW SHARE (RS) - Le moins restrictif
LOCK TABLE vol IN ROW SHARE MODE;
-- Permet: SELECT, INSERT, UPDATE, DELETE par autres sessions
-- Empêche: EXCLUSIVE lock par autres

-- Mode ROW EXCLUSIVE (RX)
LOCK TABLE vol IN ROW EXCLUSIVE MODE;
-- Permet: SELECT par autres
-- Empêche: SHARE, EXCLUSIVE locks

-- Mode SHARE (S)
LOCK TABLE vol IN SHARE MODE;
-- Permet: SELECT par autres, SHARE lock par autres
-- Empêche: INSERT, UPDATE, DELETE, EXCLUSIVE

-- Mode SHARE ROW EXCLUSIVE (SRX)
LOCK TABLE vol IN SHARE ROW EXCLUSIVE MODE;
-- Permet: SELECT par autres
-- Empêche: Toute modification, autres SHARE locks

-- Mode EXCLUSIVE (X) - Le plus restrictif
LOCK TABLE vol IN EXCLUSIVE MODE;
-- Permet: SELECT par autres
-- Empêche: Tout le reste

-- Avec NOWAIT (ne pas attendre si impossible)
LOCK TABLE vol IN EXCLUSIVE MODE NOWAIT;
```

### 4.2 Scénario de test de verrouillage

```sql
-- SESSION 1                              -- SESSION 2
LOCK TABLE vol IN EXCLUSIVE MODE;
                                          -- Essayer de modifier
                                          UPDATE vol SET hd = 1400
                                          WHERE vol# = 100;
                                          -- BLOQUÉ!

                                          -- Ou essayer de verrouiller
                                          LOCK TABLE vol IN SHARE MODE NOWAIT;
                                          -- ORA-00054: resource busy

COMMIT;  -- Libère le verrou
                                          -- Maintenant débloqué
```

### 4.3 Requêtes de visualisation des verrous

```sql
-- 1. Voir les transactions actives
SELECT t.addr, t.xidusn, t.xidslot, t.xidsqn,
       t.status, t.start_time, s.sid, s.username
FROM v$transaction t
JOIN v$session s ON t.addr = s.taddr;

-- 2. Voir les verrous actifs (v$lock)
SELECT sid, type, id1, id2,
       DECODE(lmode, 0,'None', 1,'Null', 2,'Row-S',
              3,'Row-X', 4,'Share', 5,'S/Row-X', 6,'Exclusive') AS mode_held,
       DECODE(request, 0,'None', 1,'Null', 2,'Row-S',
              3,'Row-X', 4,'Share', 5,'S/Row-X', 6,'Exclusive') AS mode_requested,
       block
FROM v$lock
WHERE type IN ('TX','TM');

-- 3. Voir les verrous DML avec noms d'objets
SELECT session_id, owner, name, mode_held, mode_requested, blocking_others
FROM dba_dml_locks;

-- 4. Voir les objets verrouillés
SELECT lo.session_id, lo.oracle_username, o.object_name, o.object_type,
       DECODE(lo.locked_mode, 0,'None', 1,'Null', 2,'Row-S',
              3,'Row-X', 4,'Share', 5,'S/Row-X', 6,'Exclusive') AS lock_mode
FROM v$locked_object lo
JOIN dba_objects o ON lo.object_id = o.object_id;

-- 5. Qui bloque qui?
SELECT SUBSTR(TO_CHAR(session_id),1,5) "SID",
       SUBSTR(lock_type,1,15) "Lock Type",
       SUBSTR(mode_held,1,15) "Mode Held",
       SUBSTR(blocking_others,1,15) "Blocking?"
FROM dba_locks
WHERE blocking_others = 'Blocking';

-- 6. Sessions bloquantes et en attente
SELECT * FROM dba_blockers;  -- Qui bloque
SELECT * FROM dba_waiters;   -- Qui attend

-- 7. Requête complète: blockers et waiters avec détails
SELECT
    l1.sid AS blocking_sid,
    s1.username AS blocking_user,
    l2.sid AS waiting_sid,
    s2.username AS waiting_user,
    o.object_name AS locked_object,
    l1.type AS lock_type
FROM v$lock l1
JOIN v$lock l2 ON l1.id1 = l2.id1 AND l1.id2 = l2.id2
JOIN v$session s1 ON l1.sid = s1.sid
JOIN v$session s2 ON l2.sid = s2.sid
JOIN v$locked_object lo ON l1.sid = lo.session_id
JOIN dba_objects o ON lo.object_id = o.object_id
WHERE l1.block = 1 AND l2.request > 0;
```

### 4.4 Exemple complet avec interprétation

```sql
-- SESSION 1: Verrouiller VOL
LOCK TABLE vol IN EXCLUSIVE MODE;

-- SESSION 2: Tenter de modifier (sera bloqué)
UPDATE vol SET hd = 1500 WHERE vol# = 100;

-- SESSION 3 (DBA): Analyser la situation
SELECT session_id "SID", lock_type, mode_held, blocking_others
FROM dba_locks WHERE lock_type = 'DML';
```

**Résultat attendu**:

```
SID   Lock Type    Mode Held    Blocking?
----  -----------  -----------  ----------
145   DML          Exclusive    Blocking
178   DML          None         Not Blocking
```

**Interprétation**:
- Session 145 détient un verrou EXCLUSIVE sur la table
- Session 178 est en attente (mode_held = None, mais elle a fait une demande)
- La colonne "Blocking?" indique que 145 bloque d'autres sessions
