# L-Design 0.3.29 za Windows 11

1. Preuzmi cijeli ZIP, ne pojedinačne datoteke iz repozitorija.
2. Desni klik na ZIP → **Izdvoji sve / Extract all**.
3. Zatvori postojeći L-Design. Ako je uz sat: desni klik na ikonu → Potpuno zatvori L-Design.
4. U izdvojenoj mapi dvaput klikni **POKRENI-INSTALACIJU.cmd**.
5. Pričekaj pripremu i klikni **Nadogradi** (ili **Instaliraj** kod prve instalacije), zatim **Završi**.
6. Za postojeći vlasnički prostor odaberi **Administrator** i jednom se prijavi. Izvorni vlasnik može uključiti **Zapamti vlasničku prijavu samo na ovom računalu**; ta prijava ostaje samo na tom uređaju. Ostali administratori i dalje se ponovno prijavljuju. Za vlastiti novi račun odaberi **Napravi L-Design svojim**, a kasnije **Moj korisnički račun**. **Gost** otvara odvojen probni prostor bez lozinke.

Nova javna instalacija nema vlasnički račun ni spremljene administratorske prijave. Svaki novi korisnik ima vlastite lokalne podatke i povezuje vlastite mail, AI i Meta račune.

Sve potrebno je uključeno. Ne treba instalirati Docker, Node.js, PostgreSQL ili druge pakete. Mapa `paket` priprema se automatski; ne otvaraj njezine datoteke pojedinačno. Spremljeni mail računi, poruke, dokumenti i postavke ostaju sačuvani pri nadogradnji.

Koristi javnu poveznicu za cijeli instalacijski ZIP dobivenu uz ovu verziju. Ne zahtijeva GitHub prijavu niti sadrži privremeni token.

Od 0.3.23 aplikacija sama provjerava javni potpisani izvor nadogradnji. Administrator dobiva obavijest gore desno, zatim klikom preuzima nadogradnju i odabire **Instaliraj i ponovno pokreni**. Za buduće online nadogradnje nije potrebno preuzimati ZIP.

Rezervni način: administrator otvara **Postavke → Web i nadogradnje → Odaberi preuzetu nadogradnju**, bira raspakiranu mapu koja sadrži `update.json`, `PAKET.json` i `paket`, zatim **Instaliraj i ponovno pokreni**. Potpis i cijeli instalacijski paket provjeravaju se prije instalacije.

U Mailu su dostupne kategorije tvrtki i dobavljača te **Ponovno razvrstaj** za ponovnu provjeru postojećih poruka.
