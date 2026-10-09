import { visibleEmailText } from "../../../../../artifacts/api-server/src/lib/classifier-features.ts";

const payload = process.argv[2];
if (typeof payload !== "string") throw new Error("payload argument required");
process.stdout.write(visibleEmailText(payload));