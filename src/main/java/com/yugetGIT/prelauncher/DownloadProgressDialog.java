package com.yugetGIT.prelauncher;

import com.yugetGIT.config.StateProperties;
import com.yugetGIT.core.git.GitBootstrap;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

public class DownloadProgressDialog {

    public static void showDialog() {
        JDialog dialog = new JDialog(null, "yugetGIT — Downloading Components", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel statusLabel = new JLabel("Downloading Components...");
        panel.add(statusLabel);

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setIndeterminate(true);
        panel.add(progressBar);

        JButton cancelButton = new JButton("Cancel");
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);

        dialog.add(panel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);

        SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() throws Exception {
                // We'll call to GitBootstrap to handle the actual download
                GitBootstrap.downloadPortableGit(
                    progress -> {
                        if (progressBar.isIndeterminate()) progressBar.setIndeterminate(false);
                        progressBar.setValue(progress);
                        statusLabel.setText("Downloading Components... " + progress + "%");
                    },
                    () -> {
                        dialog.dispose();
                        GitBootstrap.extractAndValidate();
                        StateProperties.setFirstRun(false);
                        StateProperties.setBackupsEnabled(true);
                    },
                    () -> {
                        dialog.dispose();
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                            null,
                            "yugetGIT could not download required components.\nPlease check your internet connection and try again.",
                            "yugetGIT Download Failed",
                            JOptionPane.ERROR_MESSAGE
                        ));
                        StateProperties.setFirstRun(true);
                    }
                );
                return null;
            }
        };

        cancelButton.addActionListener(e -> {
            worker.cancel(true);
            dialog.dispose();
            StateProperties.setBackupsEnabled(false);
            StateProperties.setFirstRun(false);
        });

        worker.execute();
        dialog.setVisible(true);
    }
}