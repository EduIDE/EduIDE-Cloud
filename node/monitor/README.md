# Monitor

VSCode extension to monitor Theia Cloud hosted VSCode/Theia products.
The extension will expose a rest service, where an external service can ping the extension to get information about the pod.

The extension is designed to facilitate the addition of further modules in the future.

For this, the extension reads environment variables to check the activation of each corresponding module.

For an example on setting these environment variables, take a look at the `Run extension` launch config in the `.vscode/launch.json` file.

```json
"THEIACLOUD_MONITOR_PORT": "8081",
"THEIACLOUD_MONITOR_ENABLE_ACTIVITY_TRACKER": "true"
```

## List of modules

| Name                  | description                                                                                                    | ENV to enable                              |
| --------------------- | -------------------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| MessagingModule       | Allows the backend to send messages to be displayed to the user                                                | (always enabled)                           |
| ActivityTrackerModule | Allows the backend to ask about the users last activity and show a warning if the pod is about to be timed out | THEIACLOUD_MONITOR_ENABLE_ACTIVITY_TRACKER |

## What counts as activity

Only events a user causes reset the inactivity timer: edits and saves in `file` or `untitled` documents, editor selection, scrolling and focus, window state, debug and task runs, and file operations.
Events that extensions fire on their own do not count, because they kept idle sessions alive until their lifetime limit.
Examples are output channel appends (the Artemis extension logs every 5 seconds), diagnostics, configuration writes and documents opened in the background.
Other extensions can still report activity explicitly with the `theia.cloud.monitor.activity.report` command.

When the operator asks for the "Are you still here?" dialog, the monitor also executes `eduide.session.timeoutWarning`.
The EduIDE frontend registers that command and blinks the tab title, and it reports any keyboard, mouse or scroll input in the tab through `theia.cloud.monitor.activity.report`.

Run `npm test` to check these rules.

## Debugging the extension

Open this folder in vscode. A `Run Extension` debug config will be available. This will open a new VSCode instance with the extension installed.

## Bundle the extension

Run `npm run build:vsix` to create a `vsix` file that can be installed in VSCode/Theia.
Run `npm run update:demo:monitor` to update the `vsix` file in the `demo-theia-monitor-vscode` component.
