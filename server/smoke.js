import fs from "fs";
for (const f of ["server.js", "package.json", ".env.example"]) if (!fs.existsSync(new URL(f, import.meta.url))) throw new Error(`missing ${f}`);
console.log("SellSan server structure OK");
