# TermoDom 2.0

Izvorne Android i Windows aplikacije za grijanje, solarnu elektranu i Xiaomi kameru. Android koristi Java kontrole i Canvas, Windows WPF, a video nativni LibVLC. Nema WebViewa, Electron sučelja ni emulatora.

## Preuzimanje i instalacija

- [Android APK 2.0.0](https://github.com/leo25matko-boop/termo/releases/download/v2.0.0/TermoDom-Android-2.0.0.apk) — Android 8 ili noviji. Otvorite APK na mobitelu i odaberite instalaciju. Potpis je isti kao u ranijem TermoDom APK-u.
- [Windows EXE instalater 2.0.0](https://github.com/leo25matko-boop/termo/releases/download/v2.0.0/TermoDom-Setup-2.0.0-x64.exe) — Windows 10/11 x64. Runtime je uključen; instalacija je namijenjena trenutačnom korisniku.
- [Windows prenosivi ZIP](https://github.com/leo25matko-boop/termo/releases/download/v2.0.0/TermoDom-Windows.zip) — raspakirajte cijeli paket i pokrenite **TermoDom.exe**.

GitHubov **Source code (zip)** sadrži razvojni kod. Za korištenje odaberite gotovi APK, EXE ili Windows ZIP.

## Grijanje

Izravna HTTPS veza s [postojećim sustavom](https://automatiziraj.se/centralno/php/novo.php): izvorna očitanja i statusi, obje etaže, 24 satna temperaturna klizača po etaži, šest postavki, deset grafikona, odabir razdoblja i CSV izvoz. Spremanje traži potvrdu i provjerava se ponovnim čitanjem. Nedostupni podaci ostaju označeni kao nedostupni.

## Solarna elektrana

U odjeljku **Solarna elektrana** odaberite **Uvezi datoteku povezivanja** ili zalijepite dijeljenu FusionSolar kiosk poveznicu. API ključ nije potreban. Osobna poveznica ostaje na uređaju i nije dio javnog repozitorija ili instalera.

Prikaz obuhvaća snagu, proizvodnju, dnevni grafikon i pokazatelje koje stvarno vraća kiosk. Kiosk pruža čitanje; upravljanje inverterom ostaje u pripremi.

## Xiaomi Mi 360 kamera

Prijava postojećim Xiaomi računom, dodatni kod ili CAPTCHA po potrebi te video nalaze se unutar TermoDoma. Za Hrvatsku je zadana regija Europa. Kamera i uređaj s TermoDomom trebaju biti na istoj lokalnoj mreži, uz internet za Xiaomi prijavu i ključeve prijenosa. Sesija se sprema šifrirano na uređaju.

Kontrole se dohvaćaju iz javne MIoT specifikacije modela i izvornog Xiaomi računa: uključivanje kamere, detekcija i praćenje pokreta, osjetljivost i raspored detekcije, noćni prikaz, lampica, postavke slike i SD snimanja kada ih model izlaže i dopušta. Brojevi svojstava i vrijednosti izbornika dolaze iz specifikacije modela. Promjene se potvrđuju ponovnim očitanjem; neuspjela očitanja ne prikazuju se kao uključena ili isključena postavka.

Zakretanje i nagib imaju ograničenu podršku za poznate MISS modele chuangmi.camera.026c02 i chuangmi.camera.026c05, tijekom prikaza uživo. Kontrole gledanja uključuju zvuk, fotografiju stvarnog kadra, cijeli zaslon i izbor kvalitete gdje je podržan. Android može spremiti lokalnu video snimku kada ga prijenos podržava. Dvosmjerni razgovor, cloud/SD reprodukcija arhive i ostale nepovezane mogućnosti označene su **U pripremi**.

Točan korisnikov model, firmware, dozvole računa i stvarni video moraju se potvrditi njegovom prijavom i kamerom. Razvojne snimke zaslona sadrže jasno označene testne prikaze i nisu dokaz veze s korisnikovom kamerom. API ključevi se ne traže.

## Ažuriranja i izrada

Windows u pozadini može automatski preuzeti ZIP novijeg stabilnog GitHub izdanja uz provjeru objavljene SHA256 vrijednosti. Preuzimanje ne pokreće instalaciju. Postavke, ručna provjera i mapa preuzimanja dostupni su u aplikaciji.

Upute za izradu i provjere: [NATIVE-2.0.md](NATIVE-2.0.md), [Android](android/README.md), [Windows](windows/README.md), [modul kamere](scripts/CAMERA-ENGINE.md). Privatni Android ključ za potpisivanje ostaje izvan repozitorija.

Stari Node/Electron paket čuva izvore arhivirane verzije 1.0. Aktualne instalere gradi nativni Android/Windows workflow. Stari Electron workflow pokreće se samo ručno.
