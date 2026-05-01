const assert = require("assert");
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const packageScript = path.join(root, "scripts", "package-memory-windows.ps1");
const verifyScript = path.join(root, "scripts", "verify-memory-windows.ps1");

function read(file) {
  return fs.readFileSync(file, "utf8");
}

assert.ok(fs.existsSync(packageScript), "package-memory-windows.ps1 must exist");
assert.ok(fs.existsSync(verifyScript), "verify-memory-windows.ps1 must exist");

const packageText = read(packageScript);
assert.match(packageText, /lark-cli-memory-windows/, "package script must target the fixed delivery directory");
assert.match(packageText, /go build -trimpath/, "package script must build with go build -trimpath");
assert.match(packageText, /README\.md/, "package script must generate delivery README");
assert.match(packageText, /execution-review\.zh-CN\.md/, "package script must generate Chinese execution review notes");
assert.match(packageText, /examples/, "package script must generate examples directory");
assert.match(packageText, /verify\.ps1/, "package script must copy or generate verify.ps1");

const verifyText = read(verifyScript);
assert.match(verifyText, /auth status/, "verify script must mention manual auth status validation");
assert.match(verifyText, /schema --help/, "verify script must validate schema help");
assert.match(verifyText, /doc/i, "verify script must cover doc examples");
assert.match(verifyText, /wiki/i, "verify script must cover wiki examples");
assert.match(verifyText, /drive/i, "verify script must cover drive examples");
assert.match(verifyText, /--dry-run/, "verify script must include a dry-run check");

console.log("memory windows package script tests passed");
