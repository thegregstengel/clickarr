# Design sheet generator

`sheets.js` renders `../palette.png` and `../type-specimen.png` (plus SVG sources) from the tokens in the design language. It reuses the brand tooling's dependencies and font:

```bash
cd brand/tools && npm install && npm run fetch-font
cd ../../docs/design/tools
NODE_PATH=../../../brand/tools/node_modules node sheets.js .. ../../../brand/tools/.fonts/extras/ttf
```

Edit the token arrays at the top of `sheets.js` when `design-language.md` section 2 changes, then re-run.
