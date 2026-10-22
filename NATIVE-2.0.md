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

Ostale integracije sa slikovnih referenci označene su **U pripremi** dok ne postoji stvarno povezivanje. Referentne slike koriste se samo za smjer oblikovanja.

## Izrada i provjera

Android: Java 17, Android SDK 36, Gradle wrapper u android/. Release se potpisuje privatnim ključem izvan repozitorija. CI gradi aplikaciju, izvršava parsere i provjere spremanja te snima nativne zaslone u Android emulatoru. Potpis ostaje kompatibilan s prethodnim TermoDom APK-om.

Windows: izvorni projekt windows/TermoDom.Windows.csproj, .NET 8 WPF i samostalni win-x64 paket. Inno Setup priprema EXE instalaciju po korisniku. GitHub workflow windows-native.yml izvodi provjere i priprema instalacijski paket. Runtime aplikacije dolazi s paketom.

Automatske provjere koriste testne odgovore i ne šalju upravljačke naredbe stvarnom grijanju. Testni prikazi nisu dokaz prijave u korisnikov Xiaomi račun ili reprodukcije njegove kamere.
