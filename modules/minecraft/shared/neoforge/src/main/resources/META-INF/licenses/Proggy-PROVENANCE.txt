# ProggyTiny provenance

- Font: ProggyTiny
- Upstream: <https://github.com/bluescan/proggyfonts>
- Upstream commit: `139ec08a38096161291792313ef5803fc4f0e37b`
- Source path: `ProggyOriginal/ProggyTiny.pcf.gz`
- PCF archive SHA-256: `a8beed341cfa79272b80c48d3237c417ff7b155468b95a634a70ba918d6d503a`
- Normalized BDF SHA-256: `62179ada849580184ac1fafbb9fee57b360bf943328c135b35fd6b4e98ff7e55`
- Metrics: 6x10 cell, ascent 8, descent 2

The committed BDF was produced without scaling by pcf2bdf 1.07 at commit
`4e80d7fa069b4be08ec4e23e4d5086ef046e86aa`:

```sh
pcf2bdf -o ProggyTiny.bdf ProggyTiny.pcf.gz
```

Normal builds consume the committed BDF and do not require pcf2bdf or network
access.
