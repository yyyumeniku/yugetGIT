package com.yugetGIT.prelauncher;

import com.yugetGIT.config.StateProperties;
import com.yugetGIT.core.git.GitBootstrap;

import javax.swing.JOptionPane;
import javax.swing.JLabel;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Image;
import java.net.URL;

public class PreLaunchGitDialog {

    public static void showIfNeeded() {
        if (!StateProperties.isFirstRun() && !StateProperties.isBackupsEnabled()) return;
        
        GitBootstrap.resolveFromPath();
        if (!StateProperties.isFirstRun() && GitBootstrap.isGitResolved()) return;

        try {
            SwingUtilities.invokeAndWait(PreLaunchGitDialog::showDialog);
        } catch (Exception e) {
            System.err.println("[yugetGIT] Failed to show early git dialog");
            e.printStackTrace();
        }
    }

    public static void showDialog() {
        GitBootstrap.resolveFromPath();
        boolean hasGit = StateProperties.getGitPath() != null && !StateProperties.getGitPath().isEmpty();
        boolean hasLfs = StateProperties.isGitLfsAvailable();

        if (hasGit && hasLfs) {
            StateProperties.setFirstRun(false);
            StateProperties.setBackupsEnabled(true);
            return;
        }

        String missingComponent;
        if (!hasGit && !hasLfs) {
            missingComponent = "Git and Git-LFS were not found";
        } else if (!hasGit) {
            missingComponent = "Git was not found";
        } else {
            missingComponent = "Git-LFS was not found (but Git is installed)";
        }

        String[] options = {"Download Missing Components", "Check Again / System", "Skip (disable backups)"};
        
        Object[] messageContent;
        URL logoUrl = PreLaunchGitDialog.class.getResource("/assets/yugetgit/logo.png");
        String textLabel = "yugetGIT needs Git and Git-LFS to version-control your worlds.\n\n" +
                           missingComponent + " on this system.\n" +
                           "Would you like yugetGIT to download a portable copy?";
        
        if (logoUrl != null) {
            ImageIcon original = new ImageIcon(logoUrl);
            Image scaled = original.getImage().getScaledInstance(600, 250, Image.SCALE_SMOOTH);
            messageContent = new Object[]{new JLabel(new ImageIcon(scaled)), " ", textLabel};
        } else {
            messageContent = new Object[]{textLabel};
        }

        int choice = JOptionPane.showOptionDialog(
            null,
            messageContent,
            "yugetGIT — Missing Git/LFS",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            options,
            options[0]
        );

        switch (choice) {
            case 0:
                DownloadProgressDialog.showDialog();
                break;
            case 1:
                GitBootstrap.resolveFromPath();
                if (GitBootstrap.isGitResolved()) {
                    StateProperties.setFirstRun(false);
                } else {
                    showDialog(); // show again if still missing
                }
                break;
            case 2:
                StateProperties.setBackupsEnabled(false);
                StateProperties.setFirstRun(false);
                break;
            default:
                StateProperties.setBackupsEnabled(false);
                StateProperties.setFirstRun(false);
                break;
        }
    }
}