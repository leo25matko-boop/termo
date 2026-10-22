# TermoDom

Windows desktop aplikacija za upravljanje postojećim sustavom grijanja na [automatiziraj.se](https://automatiziraj.se/centralno/php/novo.php). Otvara se u vlastitom prozoru i prikazuje stvarne temperature, rasporede i grafikone sustava.

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

Detaljne korisničke upute i popis izvornih funkcija nalaze se u `UPUTE.txt` i `FUNKCIJE.txt`.
