package com.littlesauce.bareline;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * 密码库浮层。
 *
 * 两种模式：
 * - 填充模式（浏览网页时点钥匙按钮）：点一条立刻往页面里填
 * - 管理模式（面板里点「密码库」）：点一条进入编辑，可新建 / 删除
 *
 * 密码在磁盘上是 AES-GCM 密文（见 {@link Crypto}），这里拿到的是解密后的明文，
 * 只活在内存里，填充完就随 View 一起丢掉。
 */
public class VaultSheet {

    public interface OnFill {
        void onFill(Vault.Item item);
    }

    private final Activity act;
    private final ViewGroup host;
    private final Vault vault;
    private final OnFill onFill;
    /** 密码库内容变化时通知外面刷新计数 / 钥匙按钮。 */
    private Runnable onChanged;

    private final View card;
    private final LinearLayout listView;
    private final TextView title;
    private final TextView hint;
    private final TextView addBtn;
    private final View editor;
    private final EditText edLabel;
    private final EditText edUser;
    private final EditText edSecret;

    private boolean manageMode = false;
    /** 正在编辑的记录 id；null 表示新建。 */
    private String editingId = null;
    private boolean showing = false;

    public VaultSheet(Activity act, ViewGroup host, Vault vault, OnFill onFill) {
        this.act = act;
        this.host = host;
        this.vault = vault;
        this.onFill = onFill;

        LayoutInflater.from(act).inflate(R.layout.vault_sheet, host, true);
        card = host.getChildAt(0);
        listView = host.findViewById(R.id.vaultList);
        title = host.findViewById(R.id.vaultTitle);
        hint = host.findViewById(R.id.vaultHint);
        addBtn = host.findViewById(R.id.vaultAdd);
        editor = host.findViewById(R.id.vaultEditor);
        edLabel = host.findViewById(R.id.vaultLabel);
        edUser = host.findViewById(R.id.vaultUser);
        edSecret = host.findViewById(R.id.vaultSecret);

        host.findViewById(R.id.vaultOutside).setOnClickListener(v -> hide());
        addBtn.setOnClickListener(v -> startNew());
        host.findViewById(R.id.vaultCancel).setOnClickListener(v -> closeEditor());
        host.findViewById(R.id.vaultSave).setOnClickListener(v -> save());
    }

    public boolean isShowing() {
        return showing;
    }

    public void setOnChanged(Runnable r) {
        this.onChanged = r;
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    public void show(boolean manage) {
        this.manageMode = manage;
        showing = true;
        closeEditor();
        title.setText(manage ? "密码库" : "填入密码");
        hint.setText(manage ? "点一条可修改，× 删除" : "点一条立刻填入当前页面");
        addBtn.setVisibility(manage ? View.VISIBLE : View.GONE);
        refresh();

        host.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setTranslationY(dp(24));
        card.animate().alpha(1f).translationY(0f).setDuration(170).start();
    }

    public void hide() {
        if (!showing) return;
        showing = false;
        hideKeyboard();
        card.animate()
                .alpha(0f)
                .translationY(dp(24))
                .setDuration(130)
                .withEndAction(() -> {
                    host.setVisibility(View.GONE);
                    closeEditor();
                })
                .start();
    }

    // ------------------------------------------------------------ 列表

    private void refresh() {
        if (listView == null) return;
        listView.removeAllViews();
        List<Vault.Item> items = vault.list();

        if (items.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText(manageMode
                    ? "还没有记录，点右上角「+ 新建」添加"
                    : "密码库是空的，先到 ☰ 里添加");
            empty.setTextColor(0xFF8C9099);
            empty.setTextSize(12);
            empty.setPadding(dp(10), dp(14), dp(10), dp(16));
            listView.addView(empty);
            return;
        }

        for (Vault.Item it : items) {
            listView.addView(buildRow(it));
        }
    }

    private View buildRow(final Vault.Item item) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(4), dp(9));
        row.setBackgroundResource(selectableBg());

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(act);
        String label = item.label == null || item.label.isEmpty() ? "未命名" : item.label;
        name.setText(label);
        name.setTextColor(0xFF14161A);
        name.setTextSize(14);
        name.setSingleLine(true);
        col.addView(name);

        String sub = item.user == null ? "" : item.user;
        if (manageMode && item.secret != null && !item.secret.isEmpty()) {
            sub = sub.isEmpty() ? mask(item.secret) : sub + " · " + mask(item.secret);
        }
        if (!sub.isEmpty()) {
            TextView s = new TextView(act);
            s.setText(sub);
            s.setTextColor(0xFF8C9099);
            s.setTextSize(11);
            s.setSingleLine(true);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col);

        if (manageMode) {
            TextView del = new TextView(act);
            del.setText("×");
            del.setTextColor(0xFFB0B4BC);
            del.setTextSize(17);
            del.setPadding(dp(10), dp(2), dp(12), dp(2));
            del.setOnClickListener(v -> {
                vault.remove(item.id);
                refresh();
                notifyChanged();
            });
            row.addView(del);
        }

        row.setOnClickListener(v -> {
            if (manageMode) {
                startEdit(item);
            } else {
                hide();
                onFill.onFill(item);
            }
        });
        return row;
    }

    /** 只露头尾，中间用点占位 —— 管理列表里能认出来又不至于全暴露。 */
    private String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        int n = s.length();
        if (n <= 4) return "••••";
        return s.substring(0, 2) + "••••" + s.substring(n - 2);
    }

    // ------------------------------------------------------------ 编辑

    private void startNew() {
        editingId = null;
        edLabel.setText("");
        edUser.setText("");
        edSecret.setText("");
        openEditor();
    }

    private void startEdit(Vault.Item item) {
        editingId = item.id;
        edLabel.setText(item.label == null ? "" : item.label);
        edUser.setText(item.user == null ? "" : item.user);
        edSecret.setText(item.secret == null ? "" : item.secret);
        openEditor();
    }

    private void openEditor() {
        editor.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        addBtn.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        edLabel.requestFocus();
    }

    private void closeEditor() {
        editor.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        hint.setVisibility(View.VISIBLE);
        addBtn.setVisibility(manageMode ? View.VISIBLE : View.GONE);
        editingId = null;
        hideKeyboard();
    }

    private void save() {
        String label = edLabel.getText().toString().trim();
        String user = edUser.getText().toString().trim();
        String secret = edSecret.getText().toString();

        if (secret.isEmpty()) {
            Toast.makeText(act, "密码不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (label.isEmpty()) {
            label = user.isEmpty() ? "未命名" : user;
        }

        Vault.Item it = new Vault.Item(editingId, label, user, secret, 0L);
        if (editingId != null) {
            Vault.Item old = vault.find(editingId);
            if (old != null) it.ts = old.ts;
        }
        vault.put(it);
        closeEditor();
        refresh();
        notifyChanged();
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) act.getSystemService(Activity.INPUT_METHOD_SERVICE);
        View f = act.getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private int dp(int v) {
        return Math.round(act.getResources().getDisplayMetrics().density * v);
    }

    private int selectableBg() {
        android.util.TypedValue tv = new android.util.TypedValue();
        act.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    // ------------------------------------------------------------ 注入脚本

    /**
     * 生成往页面里填密码的 JS。
     *
     * 要点：
     * - 用原生 value setter 赋值，React / Vue 这类框架才能感知到变化
     * - 填完派发 input / change，再点提交按钮，做到「一下按进去就登录」
     * - 只在表单里恰好只有一个密码框时才自动提交，避免误触确认密码页
     */
    public static String buildFillJs(String secret, String user) {
        String s = JSONObject.quote(secret == null ? "" : secret);
        String u = JSONObject.quote(user == null ? "" : user);

        return "(function(secret,user){"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();"
                + "if(r.width<=0||r.height<=0)return false;var st=getComputedStyle(el);"
                + "return st.visibility!=='hidden'&&st.display!=='none'&&st.opacity!=='0';}"
                + "function setVal(el,v){var p=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(p,'value');"
                + "if(d&&d.set){d.set.call(el,v);}else{el.value=v;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'Unidentified'}));}"
                + "var pws=Array.prototype.filter.call(document.querySelectorAll('input[type=password]'),vis);"
                + "if(!pws.length){pws=Array.prototype.filter.call(document.querySelectorAll('input'),function(el){"
                + "if(!vis(el))return false;var s=((el.name||'')+' '+(el.id||'')+' '+(el.placeholder||'')).toLowerCase();"
                + "return /pass|pin|token|secret|密码/.test(s);});}"
                + "if(!pws.length)return 'nofield';"
                + "var t=pws[0];t.focus();setVal(t,secret);"
                + "if(user){var scope=t.form||document;"
                + "var cands=Array.prototype.filter.call(scope.querySelectorAll('input'),function(el){"
                + "return vis(el)&&el!==t&&el.type!=='password'&&el.type!=='hidden'&&el.type!=='submit'"
                + "&&el.type!=='button'&&el.type!=='checkbox'&&el.type!=='radio';});"
                + "if(cands.length&&!cands[0].value){cands[0].focus();setVal(cands[0],user);t.focus();}}"
                + "var f=t.form;"
                + "if(f&&f.querySelectorAll('input[type=password]').length===1){"
                + "var b=f.querySelector('button[type=submit],input[type=submit]');"
                + "if(b){b.click();return 'submitted';}"
                + "if(f.requestSubmit){f.requestSubmit();return 'submitted';}}"
                + "return 'filled';})(" + s + "," + u + ")";
    }

    /** 判断当前页面有没有可填的密码框。 */
    public static String checkFieldJs() {
        return "(function(){"
                + "function vis(el){var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "var p=document.querySelectorAll('input[type=password]');"
                + "for(var i=0;i<p.length;i++){if(vis(p[i]))return '1';}"
                + "var a=document.querySelectorAll('input');"
                + "for(var j=0;j<a.length;j++){var el=a[j];"
                + "var s=((el.name||'')+' '+(el.id||'')+' '+(el.placeholder||'')).toLowerCase();"
                + "if(/pass|pin|token|secret|密码/.test(s)&&vis(el))return '1';}"
                + "return '0';})()";
    }
}
