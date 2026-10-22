# TermoDom 2.0

Izvorne aplikacije za Android i Windows: Android Views i Canvas, odnosno Windows WPF i nativni video prikaz. Nema WebViewa, Electron sučelja ni lokalnog web poslužitelja za grijanje i energiju. Tamne kartice, tirkizni detalji i jantarni pokazatelji koriste stvarna očitanja. Nedostupne vrijednosti prikazuju se crticom.

## Grijanje

- Izravna HTTPS veza s postojećim sustavom na automatiziraj.se.
- Pregled svih izvornih temperatura, etaža, pumpi, ventilatora, pufera i statusa sinkronizacije.
- Svaka etaža ima 24 satna klizača temperature, primjenu na odabrane sate i pregled rasporeda.
- Svih šest postojećih postavki i deset izvornih grafikona, odabir razdoblja i CSV izvoz.
- Spremanje provjerava izvorni obrazac i vrijednosti ponovnim čitanjem. Mutacijski GET zahtjev ne ponavlja se automatski.
- Dostavljeni dodajPODESAVANJA.php potvrđuje ugovor n0–n23 i kat; sam PHP se ne izvršava na uređaju i ne mijenja se na poslužitelju.

## Solarna elektrana

Nativni prikaz podataka dijeljenog Huawei FusionSolar kioska: snaga, dnevna, mjesečna, godišnja i ukupna proizvodnja, krivulja proizvodnje i pokazatelji doprinosa okolišu koje vraća izvor. Kiosk omogućuje čitanje; upravljanje inverterom ostaje u pripremi. Nema izračunate potrošnje, troškova, baterije ili uvoza/izvoza kada ih izvor ne daje.

U odjeljku elektrane odaberite **Uvezi datoteku povezivanja** i lokalnu datoteku .termodom ili zalijepite izvornu dijeljenu poveznicu. API ključ nije potreban. Poveznica se sprema na uređaju; osobna konfiguracija nije dio javnog repozitorija ili instalera. Ako izvor opozove ili obnovi kiosk, uvezite novu poveznicu.

## Kamera i prijava

Kamera koristi izvorni Xiaomi račun i regiju uređaja. Podrška video prijenosu ovisi o stvarnom modelu i mrežnoj dostupnosti kamere; nikada se ne prikazuje izmišljena slika ili lažno povezano stanje. API ključevi se ne traže. Prijava, dodatna provjera identiteta i stvarna slika kamere moraju se potvrditi korisnikovim računom i uređajem.

Prijava, CAPTCHA ili kod potvrde te video nalaze se u izvornom sučelju TermoDoma. Nije potreban emulator, Google prijava ili pokretanje Xiaomi Home prozora. Aplikacija dohvaća model kamere s računa; za Hrvatsku je zadana regija Europa. Ugrađeni go2rtc transport i LibVLC dekoder podržavaju odgovarajuće Xiaomi protokole. Sesija se sprema šifrirano pomoću Android Keystorea ili Windows DPAPI-ja. Kamerin pomoćni proces sluša samo na uređaju, uz nasumičnu autentikaciju, i ne pruža web sučelje. Privatna JSON konfiguracija strogo se provjerava prije otvaranja priključaka, uključujući Androidov zapis znakova.

Postavke se izvode iz javne MIoT specifikacije točnog modela i čitaju izvornim Xiaomi računom. Uključuju podržana svojstva detekcije, praćenja, osjetljivosti, vremena detekcije, noćnog prikaza, lampice, slike i SD snimanja. Nedostupne postavke ostaju u pripremi; svako spremanje šalje jednu određenu vrijednost i ponovno čita rezultat. Ne postoji proizvoljan Xiaomi RPC ili destruktivna naredba za resetiranje ili formatiranje kartice.

Za poznate MISS modele chuangmi.camera.026c02 i chuangmi.camera.026c05 dostupni su pojedinačni pomaci motora tijekom prijenosa uživo, s ograničenjem učestalosti. Potvrda protokola nije zasebna potvrda fizičkog položaja kamere. Zvuk gledanja, fotografiranje kadra, izbor kvalitete i cijeli zaslon koriste nativni video prikaz. Android sprema lokalni video ako ga izvor podržava; snimanje završava odlaskom iz prikaza. Dvosmjerni razgovor i reprodukcija cloud/SD arhive ostaju u pripremi.

Windows pri pokretanju može automatski preuzeti ZIP novijeg stabilnog GitHub izdanja. U Postavkama su prekidač, ručna provjera i mapa preuzimanja. Preuzima se isključivo TermoDom-Windows.zip iz izvornog repozitorija, uz provjeru objavljene SHA256 vrijednosti. Preuzimanje ne pokreće instalaciju niti izvršava datoteke.

Ostale integracije sa slikovnih referenci označene su **U pripremi** dok ne postoji stvarno povezivanje. Referentne slike koriste se samo za smjer oblikovanja.

## Izrada i provjera

Android: Java 17, Android SDK 36, Gradle wrapper u android/. Release se potpisuje privatnim ključem izvan repozitorija. CI gradi aplikaciju, izvršava parsere i provjere spremanja te snima nativne zaslone u Android emulatoru. Potpis ostaje kompatibilan s prethodnim TermoDom APK-om.

Windows: izvorni projekt windows/TermoDom.Windows.csproj, .NET 8 WPF i samostalni win-x64 paket. Inno Setup priprema EXE instalaciju po korisniku. GitHub workflow windows-native.yml izvodi provjere i priprema instalacijski paket. Runtime aplikacije dolazi s paketom.

Automatske provjere koriste testne odgovore i ne šalju upravljačke naredbe stvarnom grijanju. Testni prikazi nisu dokaz prijave u korisnikov Xiaomi račun ili reprodukcije njegove kamere.
