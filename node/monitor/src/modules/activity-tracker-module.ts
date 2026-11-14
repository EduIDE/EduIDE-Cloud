import { json, Router } from 'express';
import * as vscode from 'vscode';

import { MonitorModule } from '../monitor-module';
import { isAuthorized } from '../util/util';
import { isUserDocument, isUserEdit } from './user-activity';

export const ACTIVITY_TRACKER_PATH = '/activity';
export const LAST_ACTIVITY_PATH = '/lastActivity';
export const POST_POPUP = '/popup';
export const COMMAND_ACTIVITY_REPORT_TITLE = 'theia.cloud.monitor.activity.report';
export const COMMAND_TIMEOUT_WARNING = 'eduide.session.timeoutWarning';

/**
 * This module tracks the last activity of the user.
 */
export class ActivityTrackerModule implements MonitorModule {
  protected timeInMilliseconds: number;
  protected messageAlreadyDisplayed = false;

  constructor() {
    this.timeInMilliseconds = Date.now();
    this.registerCommand();
    this.setupListeners();
  }

  registerEndpoints(router: Router): Router {
    const activityRouter = Router();
    activityRouter.use(json());
    activityRouter.get(LAST_ACTIVITY_PATH, async (req, res) => {
      if (await isAuthorized(req)) {
        res.status(200);
        res.end(this.getLastActivity().toString());
      } else {
        res.status(401);
        res.end('Unauthorized');
      }
    });

    activityRouter.post(POST_POPUP, async (req, res) => {
      if (await isAuthorized(req)) {
        console.debug('POPUP REQUESTED');
        this.createPopup();
        res.status(200);
        res.end('success');
      } else {
        res.status(401);
        res.end('Unauthorized');
      }
    });
    router.use(ACTIVITY_TRACKER_PATH, activityRouter);
    return router;
  }

  /**
   * Sets the timeInMilliseconds to the current timestamp
   * @param reason optional parameter to log a reason for the activity
   */
  protected reportActivity(reason?: string): void {
    this.messageAlreadyDisplayed = false;
    this.timeInMilliseconds = Date.now();
    console.debug(`Activity reported: ${this.formatTime(this.timeInMilliseconds)} (${reason ?? 'unknown'})`);
  }

  private formatTime(timeInMilliseconds: number): string {
    const date = new Date(timeInMilliseconds);
    return date.toISOString();
  }

  /**
   * @returns the timeInMilliseconds timestamp
   */
  protected getLastActivity(): number {
    return this.timeInMilliseconds;
  }

  /**
   * Registers a command that can be executed to report activity in custom behavior
   */
  protected registerCommand(): void {
    vscode.commands.registerCommand(COMMAND_ACTIVITY_REPORT_TITLE, reason =>
      this.reportActivity(`${reason ?? 'activity'} (via command)`)
    );
  }

  /**
   * Creates a popup to the user to ask if he is still active
   * If the user confirms an activity is reported
   */
  protected createPopup(): void {
    if (!this.messageAlreadyDisplayed) {
      const options: vscode.MessageOptions = {
        detail: 'This session ends soon because nobody used it for a while. Click Yes to keep working.',
        modal: true
      };

      // Lets the EduIDE frontend flag the browser tab, so a student in another tab notices the dialog.
      // Rejects when no frontend registered the command, which is fine.
      vscode.commands.executeCommand(COMMAND_TIMEOUT_WARNING).then(undefined, () => undefined);

      const yesOption: vscode.MessageItem = { title: 'Yes', isCloseAffordance: true };

      const message = vscode.window.showInformationMessage('Are you still here?', options, yesOption);
      this.messageAlreadyDisplayed = true;

      message.then(answer => {
        if (answer === yesOption) {
          this.reportActivity('Popup dialog was confirmed');
        } else {
          this.messageAlreadyDisplayed = false;
        }
      });
    }
  }

  /**
   * Sets up the VSCode extension API listeners that report activity.
   * Only events a user causes belong here. Events that extensions or language servers fire on their own
   * (diagnostics, configuration writes, output channels, documents opened in the background) keep an idle
   * session alive until its lifetime limit.
   */
  protected setupListeners(): void {
    vscode.debug.onDidChangeActiveDebugSession(() => this.reportActivity('changeDebugSession'));
    vscode.debug.onDidChangeBreakpoints(() => this.reportActivity('changeBreakpoints'));
    vscode.debug.onDidStartDebugSession(() => this.reportActivity('startDebugSession'));
    vscode.debug.onDidTerminateDebugSession(() => this.reportActivity('terminateDebugSession'));
    // vscode.extensions.onDidChange(() => this.reportActivity(`extensionsChanged`));
    vscode.tasks.onDidEndTask(() => this.reportActivity('taskEnd'));
    vscode.tasks.onDidEndTaskProcess(() => this.reportActivity('taskEndProcess'));
    vscode.tasks.onDidStartTask(() => this.reportActivity('startTask'));
    vscode.tasks.onDidStartTaskProcess(() => this.reportActivity('startTaskProcess'));
    vscode.window.onDidChangeActiveColorTheme(() => this.reportActivity('changeColorTheme'));
    // vscode.window.onDidChangeActiveNotebookEditor(() => this.reportActivity(`changeNotebookEditor`));
    vscode.window.onDidChangeActiveTerminal(() => this.reportActivity('changeTerminal'));
    vscode.window.onDidChangeActiveTextEditor(() => this.reportActivity('changeTextEditor'));
    // vscode.window.onDidChangeNotebookEditorSelection(() => this.reportActivity(`changeNotebookEditorSelection`));
    // vscode.window.onDidChangeNotebookEditorVisibleRanges(() => this.reportActivity(`changeNotebookEditorVisibleRanges`));
    // vscode.window.onDidChangeTerminalState(() => this.reportActivity(`changeTerminalState`));
    vscode.window.onDidChangeTextEditorOptions(() => this.reportActivity('changeTextEditorOptions'));
    vscode.window.onDidChangeTextEditorSelection(() => this.reportActivity('changeTextEditorSelection'));
    vscode.window.onDidChangeTextEditorViewColumn(() => this.reportActivity('changeTextEditorViewColumn'));
    vscode.window.onDidChangeTextEditorVisibleRanges(() => this.reportActivity('changeTextEditorVisibleRanges'));
    // vscode.window.onDidChangeVisibleNotebookEditors(() => this.reportActivity(`changeVisibleNotebookEditors`));
    vscode.window.onDidChangeVisibleTextEditors(() => this.reportActivity('changeVisibleTextEditors'));
    vscode.window.onDidChangeWindowState(() => this.reportActivity('changeWindowState'));
    // vscode.workspace.onDidChangeNotebookDocument(() => this.reportActivity(`changeNotebookDocument`));
    vscode.workspace.onDidChangeTextDocument(e => isUserEdit(e) && this.reportActivity('changeTextDocument'));
    vscode.workspace.onDidChangeWorkspaceFolders(() => this.reportActivity('changeWorkspaceFolders'));
    // vscode.workspace.onDidCloseNotebookDocument(() => this.reportActivity(`closeNotebookDocument`));
    vscode.workspace.onDidCreateFiles(() => this.reportActivity('createFiles'));
    vscode.workspace.onDidDeleteFiles(() => this.reportActivity('deleteFiles'));
    vscode.workspace.onDidGrantWorkspaceTrust(() => this.reportActivity('grantWorkspaceTrust'));
    // vscode.workspace.onDidOpenNotebookDocument(() => this.reportActivity(`openNotebookDocument`));
    vscode.workspace.onDidRenameFiles(() => this.reportActivity('renameFiles'));
    // vscode.workspace.onDidSaveNotebookDocument(() => this.reportActivity(`saveNotebookDocument`));
    vscode.workspace.onDidSaveTextDocument(d => isUserDocument(d) && this.reportActivity('saveTextDocument'));
    vscode.workspace.onWillCreateFiles(() => this.reportActivity('willCreateFiles'));
    vscode.workspace.onWillDeleteFiles(() => this.reportActivity('willDeleteFiles'));
    vscode.workspace.onWillRenameFiles(() => this.reportActivity('willRenameFiles'));
    vscode.workspace.onWillSaveTextDocument(e => isUserDocument(e.document) && this.reportActivity('willSaveTextDocument'));
  }
}
