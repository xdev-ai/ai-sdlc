const vscode = require("vscode");
const { execFile } = require("child_process");

function run(command, args, options) {
  return new Promise((resolve, reject) => execFile(command, args, options, (error, stdout, stderr) => error ? reject(Object.assign(error, { stdout, stderr })) : resolve({ stdout, stderr })));
}

function activate(context) {
  context.subscriptions.push(vscode.commands.registerCommand("aisdlc.validate", async () => {
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) { vscode.window.showErrorMessage("AI-SDLC validation requires an open workspace."); return; }
    const configuration = vscode.workspace.getConfiguration("aisdlc");
    const cli = configuration.get("cliPath", "aisdlc");
    const output = vscode.window.createOutputChannel("AI-SDLC"); output.show(true); output.appendLine("Running deterministic AI-SDLC validation…");
    try { const result = await run(cli, ["validate", "--format", "junit"], { cwd: folder.uri.fsPath }); output.append(result.stdout); output.append(result.stderr); vscode.window.showInformationMessage("AI-SDLC validation completed. See the AI-SDLC output channel."); }
    catch (error) { output.append(error.stdout || ""); output.append(error.stderr || error.message); vscode.window.showErrorMessage("AI-SDLC validation failed. See the AI-SDLC output channel."); }
  }));
  context.subscriptions.push(vscode.commands.registerCommand("aisdlc.openPortal", async () => {
    const portalUrl = vscode.workspace.getConfiguration("aisdlc").get("portalUrl", "");
    if (!portalUrl) { vscode.window.showInformationMessage("Configure aisdlc.portalUrl before opening the governance portal."); return; }
    await vscode.env.openExternal(vscode.Uri.parse(portalUrl));
  }));
}
function deactivate() {}
module.exports = { activate, deactivate, run };
