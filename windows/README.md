# TermoDom Windows 2.0

Izvorna Windows aplikacija izrađena u WPF-u za .NET 8. Ne koristi Electron, WebView ni lokalni web-poslužitelj. Samostalni instalater uključuje potreban .NET runtime.

## Izrada

```powershell
dotnet restore TermoDom.Windows.csproj --runtime win-x64 --configfile NuGet.Config
dotnet publish TermoDom.Windows.csproj -c Release -r win-x64 --self-contained true --no-restore -p:PublishSingleFile=false -o publish
```

Iz `publish/TermoDom.exe --verify-native=PUTANJA` pokreće se izričit način provjere sa sintetičkim ulazima. Obuhvaća parsere, sve zaslone i stvarne izvorne Windows kontrole. Provjere zapisivanja koriste zamjenski transport i ne šalju naredbe pravom regulatoru.

Workflow `windows-native.yml` izrađuje instalater pomoću službenog Inno Setup prevoditelja na Windows runneru te sprema osam snimaka i izvještaj provjere.

## Povezivanja

Grijanje koristi izvorni automatiziraj.se regulator. Rasporedi imaju 24 klizača i izvorne granice; sve promjene traže potvrdu i provjeravaju se ponovnim čitanjem. HTTP prijenos naredbe izvodi se jednom, bez automatskog ponavljanja.

FusionSolar koristi postojeću kiosk poveznicu. Poveznica i njezina oznaka nisu ugrađene u javni kod ili instalater. Unose se u izvornom Windows polju ili uvoze kroz datoteku `.termodom` oblika `{ "version": 1, "solarKioskUrl": "izvorna HTTPS kiosk poveznica" }`. Povezivanje se sprema samo u korisnički LocalAppData. Kiosk pruža pregled proizvodnje; ne prikazuju se nepodržane naredbe inverteru.

Opcije bez izvora označene su kao U pripremi. Nedostajuća mjerenja prikazuju se kao crta. Sintetičke vrijednosti dostupne su samo u izričitom načinu provjere.
