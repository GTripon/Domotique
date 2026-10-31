# Domotique — Volets roulants MyHome (OpenWebNet)

# Application Android qui pilote **des volets roulants** Legrand / BTicino (bus MyHome).

---

## 1. Ce que fait l'application

| Fonction | Détail |
|---|---|
| MONTER / DESCENDRE / STOP par volet | 9 cartes, une par volet |
| Slider de position | envoie l'ordre, puis laisse tourner la montre interne jusqu'à la position demandée |
| Tout ouvrir / Tout fermer / Tout stopper | commandes individuelles envoyées à la suite, espacées de 120 ms |
| Demande d'état | `*#2*<WHERE>##` par volet, réponse rattachée à sa demande |
| Bandeau « Dernier échange » | sur l'écran d'accueil : la dernière trame partie **et** la vraie réponse reçue, avec le délai mesuré |
| Console des trames | toutes les trames du port, dans les deux sens, horodatées |
| Journal des trames échangées | version « phrase lisible » de la même information |
| Surveillance du bus | liste passive des adresses WHERE entendues sur le bus, avec « Affecter » / « Créer ce volet » |
| Mode essai | les commandes sont journalisées mais jamais écrites sur le socket |
| Réglages | IP, port, temps de course, nom / pièce / adresse de chaque volet |

Aucune authentification, aucun handshake : l'application n'est utilisée que depuis le réseau local.

---

## 2. Architecture

Trois couches, dans ce sens d'appel uniquement : `own/` → `data/` → `ui/`.

```
app/src/main/java/com/example/domotique1/
├── MainActivity.kt          switch écran principal / Réglages (un simple booléen, pas de nav lib)
├── DomotiqueApp.kt          Application : déclenche la connexion au démarrage
├── own/                     couche protocol : seul code qui touche des sockets
│   ├── OpenWebNet.kt         grammaire OWN + constructeurs de trames + OwnFrame.parse()
│   ├── OpenWebNetLink.kt     UNE session TCP : connexion, lecture, tramage, keepalive, reconnexion
│   ├── MyHomeGateway.kt      les 2 sessions ensemble : envoi, ACK/NACK/BUSY, appariement, statut
│   └── TrafficBus.kt         journal horodaté de tout ce qui traverse le socket (les 2 sens)
├── data/                    couche metier
│   ├── Shutter.kt           volet (config) + ShutterState (etat en direct) + 9 volets par defaut
│   ├── GatewaySettings.kt   ip/port/mode essai + persistance SharedPreferences
│   └── VoletsRepository.kt  singleton global : commandes, etats, journal, surveillance du bus
└── ui/
    ├── VoletsScreen.kt      écran d'accueil (bandeau + cartes volets)
    ├── SettingsScreen.kt    Réglages (5 cartes)
    └── theme/               thème Material 3
```

Pas de dépendance tierce : ni library OpenWebNet, ni ViewModel, ni navigation.
La seule dépendance UI est le **Compose BOM**. L'état est partagé entre les écrans et survit
aux rotations grâce à un singleton d'application :

```kotlin
// data/VoletsRepository.kt
val repository = VoletsRepository.get(context)      // instance unique, portee Application
...
val volets by repository.volets.collectAsState()    // des StateFlow partout
```

---

## 3. Le protocole OpenWebNet utilisé

Trames texte ASCII, séparées par `*`, terminées par `##`. Grammaire vérifiée contre
l'implémentation de référence [openwebnet4j](https://github.com/mvalla/openwebnet4j) :

| Type de trame | Forme | Exemple |
|---|---|---|
| Commande / dispatch bus | `*WHO*WHAT*WHERE##` | `*2*1*51##` |
| Commande traduite | `*WHO*SIGN#WHAT*WHERE##` | `*2*1000#0*55##` |
| Rapport d'état | `*#WHO*WHERE*DIM*VAL…##` | `*#2*55*10*10*100*0*0##` |
| ACK / NACK / BUSY | `*#*1##` / `*#*0##` / `*#*6##` | |

Trames réellement produites ou consommées par l'application :

```
*99*0##          ouvrir la session "commandes"
*99*1##          ouvrir la session "surveillance" (evenements du bus)
*2*1*W##         MONTER le volet d'adresse W
*2*2*W##         DESCENDRE
*2*0*W##         STOP
*2*1*0##         tout monter (WHERE = 0 = adresse generale) ; idem *2*2*0## / *2*0*0##
*#2*W##          demande d'etat du volet W
*#2*W*0##        arret (rapport) ; *#2*W*1## montee ; *#2*W*2## descente
*#2*W*10*…##     rapport de position (actuateur avance) -> journalise, jamais decode en position
*#*1##           ACK, et aussi echo du maintien de session
*#*0##           NACK (adresse refusee) ; *#*6## gateway occupee
```

Tout ce qui n'est pas reconnu reste **brut** dans le journal :
`OwnFrame.parse()` ne lève jamais d'exception, une trame invalide garde son texte et
`valid == false`. C'est ce qui permet d'afficher `*#4*1*0*0228##` (émise par la passerelle
elle-même) sans crasher et sans l'interpréter. Attention : `*#4*…` **n'est pas** un accusé,
le préfixe d'un accusé est `*#*`.

---

## 4. Deux sessions TCP, et le maintien de session

`MyHomeGateway.connect(host, port)` ouvre **deux sockets** vers `host:port` :

| Session | Trame d'ouverture | Sert à |
|---|---|---|
| `CMD` | `*99*0##` | envoyer nos commandes et recevoir les accusés |
| `MON` | `*99*1##` | écouter le bus : actions faites aux interrupteurs muraux, états |

Un `OpenWebNetLink` = une session. Le lien gère **entièrement** son cycle de vie :

- `Socket.connect(...)` avec un timeout de 5 s, puis `soTimeout = keepaliveMs` ;
- chaque expiration de lecture (`SocketTimeoutException`) déclenche une **trame de maintien**
  `*#*1##` (`KEEPALIVE_MS = 20 000 ms`, paramètre du constructeur) ;
- une lecture qui renvoie `-1` est distinguée d'un timeout : « le flux a été fermé par la
  passerelle », puis reconnexion ;
- **découpage réel en trames** : le tampon est coupé sur `##` (`extractFrames`), une lecture TCP
  pouvant contenir zéro, une ou plusieurs trames, et une trame pouvant arriver en plusieurs
  lectures. Le reliquat est gardé dans `pending` (coupé au-delà de `MAX_PENDING_CHARS = 4000`) ;
- reconnexion automatique tant que la session est active, avec un délai de
  `1,5 s × nombre d'échecs`, plafonné à 30 s ;
- chaque trame entrante ou sortante est écrite dans le `TrafficBus` avant d'être traitée.

Historique important : les premières versions appelaient « trame » **une lecture TCP entière**.
Comme la passerelle envoie plusieurs trames dans un seul segment, tout était décalé d'un cran et
donnait de fausses latences de 25 s à 9 min. Le découpage sur `##` a supprimé cet artefact :
les mesures affichées aujourd'hui sont les vraies.

---

## 5. Cycle de vie d'une commande

```
Appui sur MONTER (Chambre 1, WHERE 51)
  │
  ├─ VoletsRepository.sendMovement()      met l'etat local en "montee" + lance une estimation
  │
  ├─ MyHomeGateway.send("*2*1*51##", queued = true, label = "Chambre 1 -> MONTER")
  │     ├─ executor "own-writer" -> écriture sur le socket CMD
  │     ├─ TrafficBus : "CMD > *2*1*51##  COMMANDE  Chambre 1"
  │     └─ sentQueue (file d'attente FIFO, péremption 10 s)
  │
  ├─ la passerelle renvoie *#*1##  (accusé)
  │     ├─ matchAck() sort la trame en tête de sentQueue
  │     ├─ lastAckLatencyMs = mesuré, lastAnswerFrame = *#*1##
  │     └─ sinon (file vide / trop ancienne) : ce n'est PAS une réponse -> see §6
  │
  └─ 45 s plus tard, startAutoStop() envoie *2*0*51## tout seul (position estimée)
```

Points de conception :

- **jamais de socket depuis le thread UI** : `send()` vérifie `link.isReady()` de façon
  synchrone, puis confie l'écriture *et* la fermeture à un executor mono-thread dénommé
  « own-writer ». C'est ce qui a éliminé une `NetworkOnMainThreadException` remontée par Compose.
- `send()` renvoie `SendResult.SENT` de façon optimiste (valeur de l'enum imbriquée dans
  `OpenWebNetLink` : `SENT`, `NOT_CONNECTED`, `IO_ERROR`) ; le résultat réel vient de l'accusé.
- Un sablier « commande en cours de route » s'affiche `BUSY_HINT_MS = 600 ms` après l'envoi
  tant qu'aucun accusé n'est revenu.
- Une action de groupe (`allUp`, `allDown`, `allStop`, `refreshAllStatus`) envoie ses trames une
  par une, espacées de `GROUP_GAP_MS = 120 ms`, pour ne pas saturer la passerelle.

---

## 6. Appariement trame ↔ réponse :

Un accusé `*#*1##` n'est **jamais** collé à l'aveugle sur la dernière commande envoyée.
Trois garde-fous :

1. **File périmée** : une commande reste dans `sentQueue` au maximum `ACK_LIFETIME_MS = 10 000 ms`.
   Au-delà, son accusé éventuel n'a plus de valeur, il est traité comme une trame quelconque.
2. **Écho de maintien** : si un `*#*1##` arrive alors que nous avons envoyé une trame de maintien
   dans les `KEEPALIVE_ECHO_MS = 15 000 ms`, il est rattaché au **maintien**, pas à un volet.
   Le compteur `keepaliveAcks`, le RTT `lastKeepaliveRttMs` et l'horodatage vivent dans le lien
   (`OpenWebNetLink.noteKeepaliveEcho`), donc l'agrégation de statut ne peut pas les effacer.
   L'écran d'accueil affiche alors : « reçue : echo du maintien de session, sans lien avec un volet ».
3. **Trame spontanée** : une trame qui ne correspond à aucune attente en place marque
   `lastAnswerIsNotReply = true` → « reçue : la passerelle a émis seule, aucune latence à mesurer »,
   et `lastAnswerLatencyMs` reste à `-1` (affiché « - »).

La latence est donc un **champ explicitement mesuré** (`GatewayStatus.lastAnswerLatencyMs`,
exposé par `answerLatencyMs`) : rempli uniquement quand un appariement a réellement eu lieu,
remis à `-1` dès qu'un nouvel envoi est en file. Même chose pour le délai de l'accusé
(`lastAckLatencyMs`) mesuré sur l'installation réelle : 72 à 130 ms.

Enfin, la passerelle **répète la même réponse sur les deux sessions** (CMD et MON).
`publishFrame()` écarte le doublon (`isDuplicate`, fenêtre `DEDUPE_MS = 900 ms`) pour que
chaque évènement n'apparaisse qu'une fois dans le journal — mais la console de trafic, elle,
montre bien les deux, parce qu'elles ont bien traversé le socket deux fois.

---

## 7. Demande d'état et rattachement de la réponse

`refreshAllStatus()` envoie `*#2*W##` pour chacun des volets configurés. Sur cette installation,
la réponse utile (`*2*0*W##`) arrive **avant** l'accusé, et parfois par la session MON.

`VoletsRepository` tient donc une table `statusRequests` (WHERE → demande, `STATUS_MATCH_MS =
5 000 ms`). `MyHomeGateway.answersRequest(request, frame)` compare WHO et WHERE : si une trame de
surveillance correspond à une demande en cours, elle est présentée comme
**« réponse à notre demande »** et journalisée « état reçu → arrêté (réponse à notre demande) »
avec le délai mesuré (71 à 95 ms relevés). Sinon, c'est de la surveillance passive (§9).

---

## 8. Position : ce que le bus sait, ce que l'application estime

Le bus **ne communique pas** la position absolue des volets de cette installation. La position
affichée est une **estimation locale** (`ShutterState.positionPercent`, `-1` = inconnue,
`hasPosition` pour le savoir) :

- chaque volet a un **temps de course** (`travelMs`, défaut `DEFAULT_TRAVEL_MS = 45 000 ms`,
  modifiable par volet et en réglage global, plafonné à 120 s) ;
- un ordre de mouvement lance `startAutoStop()` : au bout du temps de course, l'application
  envoie elle-même `*2*0*W##` et pose 0 % ou 100 % ;
- un slider calcule la durée de moteur nécessaire (`MIN_MOVE_MS = 500 ms` minimum) et l'arrête
  à ce moment-là ; sans position connue, un ordre complet fait bouger la position estimée de
  `FULL_MOVE_STEP_PCT = 100` ;
- une **position estimée n'est produite que si une position était déjà connue**.

Règle appliquée après vérification en direct : un rapport d'arrêt
(`*2*0*W##`, qu'il vienne de notre STOP, d'un STOP mural ou d'une réponse d'état) **ne change que
l'action, jamais la position**. « arrêté » ne veut pas dire « fermé ». Les cartes affichent donc 
« position inconnue » après « Demander les statuts », alors même que le journal dit
« état reçu → arrêté ». Les rapports avancés `*#2*W*10*…##` sont journalisés bruts
(« valeurs=… ») et **jamais** convertis en position.

---

## 9. Surveillance du bus (découvrir les adresses WHERE)

Les adresses WHERE réelles de l'installation ne sont pas connues d'avance. La carte
« Surveillance du bus » des Réglages exploite passivement la session MON :

- toute trame de mouvement `*2*<0|1|2>*W##` entendue sur le bus nourrit une
  `ObservedWhere(where, seen, lastRaw, lastAt)` (liste limitée à `MAX_OBSERVED = 30`) ;
- chaque ligne propose **« Affecter »** (l'adresse est collée au volet choisi, via `setWhere`)
  ou **« Créer ce volet »** (`addShutterFromObserved`, type = réglage « type des nouveaux volets ») ;
- rien n'est envoyé sur le bus pour cette découverte : il suffit d'actionner un volet **au mur**
  avec son interrupteur, l'application lit et propose.

C'est comme ça que les 9 adresses par défaut ont été établies.

---

## 10. Console de trafic et journal

Deux lectures du même évènement, deux utilités :

| | Console des trames | Journal des trames échangées |
|---|---|---|
| Conteneur | `own/TrafficBus.kt` | `VoletsRepository._log` |
| Capacité | 300 entrées | `MAX_LOG = 150` |
| Contenu | heure, session (`CMD`/`MON`), sens, trame brute, nature (`OUVERTURE`, `COMMANDE`, `KEEPALIVE`, `RECEPTION`, `RAFFALE`, `ENVOI ECHOUE`) | phrase en français : « Chambre 1 → MONTER (`*2*1*51##`) », « accusé reçu après 117 ms », « état reçu → arrêté » |
| Ordre | le plus récent en premier | le plus récent en premier |
| Purge | « Vider la console » | « Vider le journal » |

Le `TrafficBus` est unique et partagé par les deux liens : **tout** ce qui traverse le port 20000
y passe, dans les deux sens, y compris les trames que l'application ne comprend pas. Les compteurs
`Envoyées n · Reçues n · Erreurs n` viennent des mêmes enregistrements.
`record()` est écrit sans verrou (remplacement atomique d'une liste immuable) pour que le thread
lecteur ne bloque jamais.

En plus des trames, le journal garde une trace des rejets : `rejectedCount()` comptabilise les
adresses qui ont répondu NACK (la carte « État de la liaison » affiche « Adresses refusées »).

---

## 11. Écrans

**Écran d'accueil** (`VoletsScreen`)
- bandeau d'état de connexion, titre, bouton « Réglages » ;
- **Dernier échange avec la passerelle** : la trame que nous avons envoyée (avec son libellé de
  volet), et la réponse **réellement appariée** avec sa latence mesurée — ou l'un des trois cas
  d'honnêteté (« En attente de réponse. », écho de maintien, trame spontanée) ;
- « Tout ouvrir / Tout fermer / Tout stopper » ;
- une carte par volet : MONTER / STOP / DESCENDRE, barre de position, slider « position à envoyer »,
  et si l'adresse vaut 0 : « Adresse non définie : ouvrez les Réglages ».

**Réglages** (`SettingsScreen`, 5 cartes)
1. **Passerelle MyHome** : IP, port, temps de course par défaut, type des nouveaux volets,
   mode essai, « Enregistrer » / « Enregistrer et reconnecter » ;
2. **État de la liaison** : sessions CMD et MON actives ou non, trames envoyées, accusés reçus,
   trames bus reçues, veilles envoyées, **échos du maintien** et **délai du maintien**,
   reconnexions, adresses refusées, dernier envoi, dernière trame reçue, **délai de l'accusé**,
   et les boutons Connecter / Reconnecter / Déconnecter / **Demander les statuts** ;
3. **Console des trames sur le port 20000** ;
4. **Surveillance du bus** ;
5. **Mes volets** (nom, pièce, adresse WHERE, temps de course, supprimer, ajouter, « Valeurs par défaut »)
   puis **Journal des trames échangées**.

Navigation : un simple booléen `showSettings` dans `MainActivity`, avec un `BackHandler`.
`DomotiqueApp.onCreate` appelle `repository.connect()` ; « Reconnecter » ne rouvre que les liens
existants s'ils sont déjà là (`hasLinks()` → `reconnectLinks()`), sinon il passe par `connect()`.

---

## 12. Réglages et persistance

`ShutterStore` (fichier de préférences `domotique_gw`) :

| Clé | Défaut | Rôle |
|---|---|---|
| `host` | `192.168.x.x` | IP de la passerelle |
| `port` | `x` | port TCP OpenWebNet |
| `dry_run` | `false` | **mode essai** : rien n'est écrit sur le socket, tout est journalisé dans une section « ESSAI » |
| `default_travel` | 45 000 ms | temps de course des nouveaux volets |
| `default_model` | `CHAMBRE` | type proposé à la création |
| `volets` | les 9 volets par défaut | une ligne par volet, champs séparés par `\|` : `id\|nom\|piece\|where\|modele\|tempsDeCourse` |

Les champs manquants à la lecture reprennent les défauts (le format peut donc évoluer sans
casser les sauvegardes). Les noms ne peuvent pas contenir les séparateurs : ils sont nettoyés
(`|` → `/`, saut de ligne → espace). `resetToDefault()` vide la clé `volets`, ce qui fait
repartir sur `ShutterDefaults.DEFAULT`.

L'état en direct (mouvement en cours, position estimée) n'est **pas** enregistré : le bus ne le
fournit pas et il serait faux après un redémarrage. Les volets par défaut sont ceux de cette
installation : Chambre 1/2/3 → 51/52/53, Cuisine → 56, Salon 1 à 5 → 57/58/59/67/68.
L'ordre des pièces et les adresses se corrigent à la main dans la carte « Mes volets ».

Où est le keepalive ? Nulle part dans les réglages : il vit uniquement dans `OpenWebNetLink`
(`KEEPALIVE_MS = 20 000 ms`, paramètre du constructeur, pas de clé de préférence associée).

---

## 13. Permissions et prérequis matériels

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

`ACCESS_LOCAL_NETWORK` (Android 16+) est demandé au runtime depuis `MainActivity` quand
`SDK_INT >= 37` : sans lui, Android refuse les connexions TCP vers le réseau local.

- `minSdk = 24`, `targetSdk = 37`, bytecode Java 11, seule dépendance : le **Compose BOM**
  (le JDK 25 utilisé à la compilation est celui fourni par Android Studio) ;
- l'application doit être sur le **même réseau local** que la passerelle, aucun accès distant
  n'est prévu ni géré ;
- la passerelle de cette installation est un MyHome BTicino (`CN=MyHome-Bticino`, ports 80/443/x).

---

## 14. Compiler, installer, lire les traces


Trames et décisions dans logcat, par tag :

```
adb logcat -s OpenWebNetLink:V OWN-gateway:V OWN-repo:V
```

- `OpenWebNetLink` : `[CMD] TX …` / `[CMD] RX …`, `[MON] …`, connexions, fermetures, arrière ;
- `OWN-gateway` : appariement des accusés, doublons écartés ;
- `OWN-repo` : exactement les lignes du journal à l'écran (`[dir] trame :: texte`).

Pour vérifier l'appariement sans bouger les volets : Réglages → « Demander les statuts ».
Sur l'installation réelle, cela fait 9 requêtes espacées de ~120 ms, chacune suivie de
« trame … = réponse à … reçue après 71-95 ms » et d'un accusé apparié FIFO en 72-130 ms.

---

## 15. Mesures relevées sur l'installation réelle

| Mesure | Valeur |
|---|---|
| Latence de réponse de la passerelle | 70 à 200 ms |
| Délai d'un accusé | 72 à 130 ms |
| RTT de l'écho de maintien `*#*1##` | 3 à 6 ms (la passerelle répond sans toucher au bus) |
| Espace minimal entre deux trames d'un même lot | 120 ms (`GROUP_GAP_MS`) |
| Reconnexions pendant une session de test | 0 |

La passerelle émet aussi ses propres trames sans qu'on les demande (ex. `*#4*1*0*0228##`) :
elles sont affichées, jamais interprétées, et jamais comptées comme une réponse.

---

## 16. Limites connues

- La **correspondance nom de pièce ↔ adresse WHERE** est à corriger manuellement dans
  « Mes volets » : le bus n'expose pas les noms, et aucun mouvement n'est initié automatiquement
  pour les découvrir.
- Les rapports de position avancés (`DIM=10`) sont journalisés mais non décodés : leur sens exact
  n'a pas été vérifié ici.
- Un volet ne peut être piloté que si son adresse WHERE est entre 1 et 99 (`where = 0` = non configuré).
- Les groupes OWN (`WHERE` 21-25) ne sont pas utilisés : les actions de groupe sont envoyées comme
  commandes individuelles séquentielles, ou en adresse générale `*2*1*0##` pour « tout ».
- La console de trafic n'a pas de filtre « tout le bus » / « seulement nos volets ».
