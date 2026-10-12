# Custom labels

Open the SpeakTouch menu on a control and choose **Add label**. The label editor
has Save and Cancel. A saved label enables **Edit label** and **Remove label**.
An existing app-provided name, associated label, hint or grouped readable content
takes precedence; the custom label only fills missing content. Roles and states
continue to be read normally.

Labels require an app package name and a fully qualified resource ID. Password
and accessibility-sensitive nodes cannot be labelled. A node without a usable
resource ID has no label commands. Labelling does not change navigation eligibility.

The target must initially be visible and enabled before the label editor can open.
After validated editor entry, temporary loss of `isVisibleToUser` does not by
itself cancel editing or prevent Save. Successful refresh, original target/window
identity, matching package/resource ID, enabled state and password/sensitivity
checks still apply. Cancel restores focus only when it is safe to do so.

The exact `(packageName, viewIdResourceName)` pair is the matching key. Controls
with the same pair intentionally share a label, including repeated controls in
lists. Different IDs and packages remain independent. Labels survive service
restarts, app updates and Android system updates on the same device. Custom labels
do not transfer to another device. If an app reuses an old resource ID for a different
control, its saved label can also apply to that control; edit or remove it then.

Only the key and user-entered label are stored in private local preferences.
The preference file is excluded from cloud backup and device transfer. Clearing
SpeakTouch's app data or uninstalling SpeakTouch removes its labels. There is no
clipboard, network, import/export or automatic-labeling path in this version.
