// Generates assets/map/style-dark.json for the apps. Placeholders filled in at runtime:
//   {MAP_URL}  pmtiles://file://<path to the downloaded area>
//   {ASSETS}   base URL of the bundled fonts and sprites (asset://map on Android, file:// on iOS)
import { layers, namedFlavor } from "@protomaps/basemaps";
import { writeFileSync, mkdirSync } from "node:fs";

const style = {
  version: 8,
  name: "SRCS dark",
  glyphs: "{ASSETS}/fonts/{fontstack}/{range}.pbf",
  sprite: "{ASSETS}/sprites/dark",
  sources: {
    protomaps: {
      type: "vector",
      url: "{MAP_URL}",
      attribution: "© OpenStreetMap contributors, Protomaps",
    },
  },
  layers: layers("protomaps", namedFlavor("dark"), { lang: "en" }),
};
mkdirSync("../../assets/map", { recursive: true });
writeFileSync("../../assets/map/style-dark.json", JSON.stringify(style));
const fonts = new Set();
JSON.stringify(style.layers, (k, v) => { if (k === "text-font") (Array.isArray(v) ? v : []).forEach((f) => typeof f === "string" && fonts.add(f)); return v; });
console.log(`layers: ${style.layers.length}`);
console.log(`fonts: ${[...fonts].join(", ")}`);
