package ro.alynsampmobile.launcher.ui.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.joom.paranoid.Obfuscate;

import ro.alynsampmobile.launcher.R;
import ro.alynsampmobile.launcher.databinding.FragmentInformationPageBinding;
import ro.alynsampmobile.launcher.utils.SampQuery;
import ro.alynsampmobile.launcher.utils.Utils;

@Obfuscate
public class InformationPageFragment extends Fragment {
    private static final String DEFAULT_HOST = "192.168.42.41";
    private static final int DEFAULT_PORT = 7777;
    private static final String DEFAULT_LABEL = "TaxGuz:7777";
    private static final long TRIPLE_TAP_TIMEOUT_MS = 1000;

    private FragmentInformationPageBinding binding;
    private int serverPillTapCount = 0;
    private long lastServerPillTapTime = 0;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentInformationPageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Load saved username
        SharedPreferences prefs = requireActivity().getSharedPreferences("samp_settings", Context.MODE_PRIVATE);
        String savedNick = prefs.getString("nick_name", "");
        binding.usernameInput.setText(savedNick);

        // Auto-save username when edited
        binding.usernameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                requireActivity().getSharedPreferences("samp_settings", Context.MODE_PRIVATE)
                        .edit()
                        .putString("nick_name", s.toString().trim())
                        .apply();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Show saved server address (friendly label on default, real host:port on custom)
        updateServerAddressLabels();

        // Hidden feature: triple-tap the server address pill to set a custom server
        binding.serverAddressPill.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastServerPillTapTime < TRIPLE_TAP_TIMEOUT_MS) {
                serverPillTapCount++;
            } else {
                serverPillTapCount = 1;
            }
            lastServerPillTapTime = now;
            if (serverPillTapCount >= 3) {
                serverPillTapCount = 0;
                showCustomServerDialog();
            }
        });

        // Query server player count on load
        refreshServerInfo();

        // Refresh button listener
        binding.btnRefreshServer.setOnClickListener(v -> refreshServerInfo());

        // Play button listener
        binding.btnPlay.setOnClickListener(v -> launchGame());

        // Settings button listener
        binding.btnSettings.setOnClickListener(v -> {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, new SettingsPageFragment())
                    .addToBackStack(null)
                    .commit();
        });

        // Top Up button listener
        binding.btnTopUp.setOnClickListener(v -> openUrl(Utils.web));

        // Social Media buttons listeners
        binding.iconDiscord.setOnClickListener(v -> openUrl(Utils.discord));
        binding.iconInstagram.setOnClickListener(v -> openUrl(Utils.web));
        binding.iconTiktok.setOnClickListener(v -> openUrl(Utils.web));
        binding.iconWhatsapp.setOnClickListener(v -> openUrl(Utils.web));
    }

    private String getCurrentHost() {
        return requireActivity().getSharedPreferences("samp_server", Context.MODE_PRIVATE)
                .getString("host", DEFAULT_HOST);
    }

    private int getCurrentPort() {
        try {
            return Integer.parseInt(requireActivity().getSharedPreferences("samp_server", Context.MODE_PRIVATE)
                    .getString("port", String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private boolean isDefaultServer() {
        return getCurrentHost().equals(DEFAULT_HOST) && getCurrentPort() == DEFAULT_PORT;
    }

    private void updateServerAddressLabels() {
        if (binding == null) return;
        String label = isDefaultServer() ? DEFAULT_LABEL : getCurrentHost() + ":" + getCurrentPort();
        binding.serverIpText.setText(label);
        binding.serverAddressText.setText(label);
    }

    private void showCustomServerDialog() {
        Context ctx = requireContext();
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        final EditText hostInput = new EditText(ctx);
        hostInput.setHint("IP / Hostname");
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setText(getCurrentHost());
        layout.addView(hostInput);

        final EditText portInput = new EditText(ctx);
        portInput.setHint("Port");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText(String.valueOf(getCurrentPort()));
        layout.addView(portInput);

        new AlertDialog.Builder(ctx)
                .setTitle("Custom Server")
                .setMessage("Connect to a different SA-MP server. Triple-tap the address pill again to change it.")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String host = hostInput.getText().toString().trim();
                    String portStr = portInput.getText().toString().trim();
                    if (host.isEmpty()) {
                        Toast.makeText(ctx, "IP / Hostname cannot be empty!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port;
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, "Port must be a number!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (port < 1 || port > 65535) {
                        Toast.makeText(ctx, "Port must be 1-65535!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    requireActivity().getSharedPreferences("samp_server", Context.MODE_PRIVATE)
                            .edit()
                            .putString("host", host)
                            .putString("port", String.valueOf(port))
                            .apply();
                    updateServerAddressLabels();
                    refreshServerInfo();
                    Toast.makeText(ctx, "Custom server saved: " + host + ":" + port, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Reset Default", (dialog, which) -> {
                    requireActivity().getSharedPreferences("samp_server", Context.MODE_PRIVATE)
                            .edit()
                            .putString("host", DEFAULT_HOST)
                            .putString("port", String.valueOf(DEFAULT_PORT))
                            .apply();
                    updateServerAddressLabels();
                    refreshServerInfo();
                    Toast.makeText(ctx, "Server reset to default", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshServerInfo() {
        binding.onlinePlayersCount.setText("Loading...");
        final String host = getCurrentHost();
        final int port = getCurrentPort();
        new Thread(() -> {
            try {
                SampQuery sampQuery = new SampQuery(host, port);
                if (sampQuery.isOnline()) {
                    String[] infos = sampQuery.getInfo();
                    String players = infos[1];
                    String maxplayers = infos[2];
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (binding != null) {
                                binding.onlinePlayersCount.setText(players + "/" + maxplayers);
                            }
                        });
                    }
                } else {
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (binding != null) {
                                binding.onlinePlayersCount.setText("Offline");
                            }
                        });
                    }
                }
                sampQuery.close();
            } catch (Exception e) {
                e.printStackTrace();
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            binding.onlinePlayersCount.setText("23/400");
                        }
                    });
                }
            }
        }).start();
    }

    private void launchGame() {
        String nickname = binding.usernameInput.getText().toString().trim();
        if (nickname.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter Username / UCP first!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (nickname.length() > 24) {
            Toast.makeText(requireContext(), "Username cannot exceed 24 characters!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!Utils.isOnline(requireActivity())) {
            Toast.makeText(requireContext(), "No internet connection!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!Utils.appStatus()) {
            Utils.showAppStatusWarning(requireActivity());
            return;
        }

        // Save settings prefs
        requireActivity().getSharedPreferences("samp_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("nick_name", nickname)
                .putBoolean("offline_mode", false)
                .apply();

        // Save server settings prefs (custom server if set, else default)
        SharedPreferences.Editor edit = requireActivity().getSharedPreferences("samp_server", Context.MODE_PRIVATE).edit();
        edit.putString("host", getCurrentHost());
        edit.putString("port", String.valueOf(getCurrentPort()));
        edit.putString("password", "");
        edit.apply();

        Utils.saveSettings(requireContext());

        // Start game
        try {
            Intent intent = new Intent(requireActivity(), ro.alynsampmobile.game.SAMP.class);
            intent.putExtra("extra_check", "alynsampmobile1337");
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), "Failed to start game: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
