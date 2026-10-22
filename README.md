# TermoDom

Windows i Android aplikacija za upravljanje postojećim sustavom grijanja na [automatiziraj.se](https://automatiziraj.se/centralno/php/novo.php). Prikazuje stvarne temperature, rasporede i grafikone sustava.

## Android

[Preuzmite izvornu mobilnu aplikaciju TermoDom za Android 1.1.0 (APK)](https://github.com/leo25matko-boop/termo/releases/download/v1.0.0/TermoDom-Android-1.1.0.apk).

Otvorite preuzeti APK na mobitelu i dopustite instalaciju iz tog izvora ako Android to zatraži. Potreban je Android 8 ili noviji. Verzija 1.1.0 koristi izvorne Android kontrole i Canvas grafikone. Ne koristi WebView, HTML zaslone ni JavaScript runtime; za podatke i upravljanje grijanjem potreban je internet.

Mobilna verzija ima donju navigaciju, velike kontrole za dodir, satne klizače za obje etaže, sve grafikone i postavke. CSV se sprema kroz Androidov dijalog za odabir datoteke. Podaci se osvježavaju svakih 90 sekundi dok je aplikacija otvorena i po povratku u nju. Naredbe se šalju nakon potvrde. Preuzimanje Windows ZIP-a odnosi se na Windows verziju.

Android paket sadrži izvorno Java sučelje i izravnu HTTPS vezu s izvornikom. Postojeći HTML odgovori izvornika obrađuju se samo kao podaci; nikakav udaljeni kod ne izvršava se u aplikaciji. Windows sučelje ostaje zasebno. APK traži samo dozvolu za internet. Privatni ključ za potpisivanje nije dio ovog repozitorija.

## Instalacija

Za Windows 10 i 11, x64.

1. [Preuzmite instalater TermoDom 1.0.0](https://github.com/leo25matko-boop/termo/releases/latest/download/TermoDom-Setup-1.0.0-x64.exe).
2. Pokrenite **TermoDom-Setup-1.0.0-x64.exe** i slijedite korake instalacije.
3. Otvorite **TermoDom** preko prečaca na radnoj površini ili izbornika Start.

Instalater sadrži sve potrebne komponente. Instalacija je namijenjena trenutačnom korisniku; zadana mapa nalazi se unutar `%LOCALAPPDATA%`. Za očitanja i slanje promjena potrebna je internetska veza s izvornim sustavom.

Sva izdanja i datoteke dostupni su na [GitHub Releases](https://github.com/leo25matko-boop/termo/releases/latest).

## Prenosivi ZIP

[TermoDom-Windows.zip](https://github.com/leo25matko-boop/termo/releases/latest/download/TermoDom-Windows.zip) sadrži prenosivu desktop aplikaciju. Raspakirajte cijeli ZIP u zasebnu mapu i pokrenite **TermoDom.exe**. Sve datoteke i podmape iz paketa, uključujući `resources`, zadržite zajedno.

GitHubov **Source code (zip)** namijenjen je razvoju. Za korištenje aplikacije odaberite instalater ili gotovi **TermoDom-Windows.zip**.

## Upravljanje grijanjem

- Pregled temperatura kata i prizemlja, vanjske temperature, pumpi i ventilatora.
- Tri temperaturne razine pufera, sanitarna voda, peć i vrijeme izvornog sustava.
- Po 24 satna temperaturna klizača za obje etaže. Trenutačne granice izvornika: **Kat 18–33 °C**, **Prizemlje 18–25 °C**, korak **0,1 °C**.
- Primjena iste temperature na odabrane sate, pregled izmjena i spremanje cijelog rasporeda.
- Svih šest izvornih postavki sanitarne vode, vode za grijanje i klimatizacije.
- Izvorni grafikoni, uključivanje i skrivanje serija, odabir razdoblja i izvoz u CSV.
- Automatsko osvježavanje svakih 90 sekundi i provjera zapisa nakon spremanja.

Pomicanje klizača priprema izmjene. Gumb **Spremi raspored** i potvrda spremanja šalju ih stvarnom sustavu. Postavke se šalju nakon potvrde gumba za prijenos.

Oznake SYNC prikazuju izvorne tekstove; potvrda zapisa u web-sučelju ne potvrđuje zasebno stanje uređaja. Tuya poveznice za 1 h i 6 h pri izradi su vraćale HTTP 404. Dostupnost podataka ovisi o izvorniku. Ako izvornik naknadno uvede prijavu, bit će potrebno dodati podršku za tu prijavu.

## Automatsko preuzimanje s GitHuba

Pri pokretanju TermoDom u pozadini provjeri najnovije stabilno izdanje iz javnog repozitorija [leo25matko-boop/termo](https://github.com/leo25matko-boop/termo). Novi ZIP sprema u:

```text
%APPDATA%\TermoDom\downloads\TermoDom-Windows.zip
```

Već preuzeti paket provjerava se kontrolnom sumom i preskače ako odgovara izdanju. Rezultat je u `%APPDATA%\TermoDom\downloads\status.txt`. Mapu možete otvoriti tako da navedenu putanju mape unesete u adresnu traku Windows Explorera.

Preuzeti ZIP raspakirajte ručno u novu mapu nakon zatvaranja aplikacije. Preuzimanje čuva postojeću instalaciju i postavke grijanja. Za novu instaliranu verziju preuzmite aktualni EXE instalater iz GitHub Releases. Nedostupan GitHub ne sprječava rad aplikacije za grijanje.

## Zatvaranje i korisnički podaci

Zatvorite prozor aplikacije ili odaberite **Zatvori aplikaciju**. Nespremljene izmjene provjerite prije zatvaranja. Sustav grijanja nastavlja raditi prema svojim spremljenim postavkama.

Korisnički podaci nalaze se u `%APPDATA%\TermoDom`. Deinstalacija je dostupna u Windows postavkama pod **Aplikacije → TermoDom**; korisnička mapa zadržava se nakon deinstalacije.

## Razvoj i objava

U korijenu izvornog repozitorija, uz instalirani Node.js, pokrenite:

```text
npm ci
npm start
```

Za izradu Windows instalatera i desktop ZIP-a na Windowsima:

```text
npm run dist:win
```

Rezultati su u `release/`. Workflow `.github/workflows/release.yml` gradi instalater, desktop ZIP i `SHA256SUMS.txt` na oznaku oblika `v1.0.0` ili ručnim pokretanjem. Verzija oznake mora odgovarati `package.json`.

Za Android koristite JDK 17, Android SDK 36 i Build Tools 35.0.0. Gradle wrapper gradi izvorno Java sučelje, HTTPS klijent i parsere. HTML/JavaScript datoteke iz desktop verzije nisu dio APK-a:

```text
cd android
gradlew.bat :app:assembleRelease
```

Potpisivanje koristi varijable okruženja `TERMODOM_ANDROID_KEYSTORE`, `TERMODOM_ANDROID_STORE_PASSWORD`, `TERMODOM_ANDROID_KEY_ALIAS` i `TERMODOM_ANDROID_KEY_PASSWORD`. Ključ čuvajte izvan repozitorija; isti ključ potreban je za nadogradnje instalirane aplikacije. Workflow `Android APK` izrađuje nepotpisani release artifact uz izričitu opciju `-PtermodomUnsigned=true`; prije instalacije potrebno ga je privatno potpisati. Gotovi potpisani APK dostupan je u GitHub Releases.

Detaljne korisničke upute i popis izvornih funkcija nalaze se u `UPUTE.txt` i `FUNKCIJE.txt`.

Android workflow provjerava JVM testove parsera i spremanja te pokreće izvorno sučelje u Android 15 emulatoru s testnim odgovorima. Provjerava svih pet kartica, 24 satna klizača, nativne grafikone i odsutnost WebViewa. Snimke zaslona i rezultati nalaze se u artifactu `termodom-android-native-verification`. Testovi ne šalju naredbe stvarnom grijanju.
