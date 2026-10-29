# Builds app/src/main/assets/places.tsv from GeoNames dumps (CC BY 4.0, geonames.org):
#   curl -O https://download.geonames.org/export/dump/{cities5000.zip,admin1CodesASCII.txt,countryInfo.txt}
#   python3 tools/build_places.py <download dir> app/src/main/assets/places.tsv
import sys, zipfile, io, re
src, out = sys.argv[1], sys.argv[2]
countries = {}
for line in open(f"{src}/countryInfo.txt", encoding="utf-8"):
    if line.startswith("#"): continue
    f = line.rstrip("\n").split("\t")
    countries[f[0]] = f[4]
admin1 = {}
for line in open(f"{src}/admin1CodesASCII.txt", encoding="utf-8"):
    f = line.rstrip("\n").split("\t")
    admin1[f[0]] = f[2]  # ascii name
ok = re.compile(r"^[A-Za-z][A-Za-z .'-]{2,40}$")
admin_list, admin_idx = [], {}
country_list, country_idx = [], {}
rows = []
with zipfile.ZipFile(f"{src}/cities5000.zip") as z:
    data = z.read("cities5000.txt").decode("utf-8")
for line in data.splitlines():
    f = line.split("\t")
    name, ascii_, alts, lat, lon, cc, a1, pop = f[1], f[2], f[3], float(f[4]), float(f[5]), f[8], f[10], int(f[14] or 0)
    if not ok.match(ascii_): continue
    cname = countries.get(cc, cc)
    aname = admin1.get(f"{cc}.{a1}", "")
    if cname not in country_idx: country_idx[cname] = len(country_list); country_list.append(cname)
    key = (aname, cname)
    if key not in admin_idx: admin_idx[key] = len(admin_list); admin_list.append(key)
    extra = []
    if pop >= 500_000:
        for a in alts.split(","):
            a = a.strip()
            if ok.match(a) and a[0].isupper() and a.lower() != ascii_.lower() and len(a) >= 4 and a not in extra:
                extra.append(a)
            if len(extra) >= 6: break
    rows.append(f"{ascii_}\t{lat:.4f}\t{lon:.4f}\t{admin_idx[key]}\t{pop}\t{'|'.join(extra)}")
with open(out, "w", encoding="utf-8") as o:
    o.write("# GeoNames (geonames.org), CC BY 4.0. Cities with 5000+ people, regions, countries.\n")
    o.write(f"C\t{len(country_list)}\n")
    for c in country_list: o.write(c + "\n")
    o.write(f"A\t{len(admin_list)}\n")
    for a, c in admin_list: o.write(f"{a}\t{country_idx[c]}\n")
    o.write(f"P\t{len(rows)}\n")
    o.write("\n".join(rows) + "\n")
print(len(country_list), len(admin_list), len(rows))
