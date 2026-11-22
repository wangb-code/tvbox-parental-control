package com.tvbox.parental;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Fixed, pre-audited question bank shipped in assets, each item with its own voice clip. */
public final class QuizBank {

    public static final class Q {
        public String id;
        public String subject;
        public String q;
        public String[] options;
        public int answer;
        public String voice;
        public boolean big;
        public String title;
        public String[] colors;
        public String qcolor;
        /** Optional picture name in assets/images/<image>.png (object questions). */
        public String image;
        /** Clip name for THIS option order, e.g. q001_2 (without .mp3). */
        public String voiceId;
        /** All pre-recorded option orders for this question. */
        public List<Variant> variants;
    }

    /** One on-screen option order together with its matching recording. */
    public static final class Variant {
        public String[] options;
        public int answer;
        public String voiceId;
    }

    private final List<Q> all = new ArrayList<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final Random rnd = new Random();

    public static QuizBank load(Context c) {
        QuizBank bank = new QuizBank();
        try {
            InputStream in = c.getAssets().open("questions.json");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            in.close();
            JSONArray arr = new JSONObject(
                    new String(buf.toByteArray(), StandardCharsets.UTF_8))
                    .getJSONArray("questions");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Q q = new Q();
                q.id = o.getString("id");
                q.subject = o.getString("subject");
                q.q = o.getString("q");
                q.voice = o.optString("voice", "");
                q.answer = o.getInt("answer");
                q.big = o.optBoolean("big", false);
                q.title = o.isNull("title") ? null : o.optString("title", null);
                q.qcolor = o.isNull("qcolor") ? null : o.optString("qcolor", null);
                q.image = o.isNull("image") ? null : o.optString("image", null);
                JSONArray cs = o.optJSONArray("colors");
                if (cs != null) {
                    q.colors = new String[cs.length()];
                    for (int k = 0; k < cs.length(); k++) {
                        String v = cs.isNull(k) ? null : cs.optString(k, null);
                        q.colors[k] = v;
                    }
                }
                JSONArray opts = o.getJSONArray("options");
                q.options = new String[opts.length()];
                for (int k = 0; k < opts.length(); k++) {
                    q.options[k] = opts.getString(k);
                }
                JSONArray vs = o.optJSONArray("variants");
                if (vs != null && vs.length() > 0) {
                    q.variants = new ArrayList<>();
                    for (int vi = 0; vi < vs.length(); vi++) {
                        JSONObject vo = vs.getJSONObject(vi);
                        Variant v = new Variant();
                        JSONArray vopts = vo.getJSONArray("options");
                        v.options = new String[vopts.length()];
                        for (int k = 0; k < vopts.length(); k++) {
                            v.options[k] = vopts.getString(k);
                        }
                        v.answer = vo.getInt("answer");
                        v.voiceId = vo.getString("voice_id");
                        q.variants.add(v);
                    }
                    Variant first = q.variants.get(0);
                    q.options = first.options;
                    q.answer = first.answer;
                    q.voiceId = first.voiceId;
                } else {
                    q.voiceId = q.id;   // legacy single-clip banks
                }
                bank.all.add(q);
            }
        } catch (Exception e) {
            // A broken asset must never crash the service; pick() returns null
            // and the timer simply keeps the TV unlocked.
        }
        return bank;
    }

    public int size() {
        return all.size();
    }

    /**
     * Return a copy of the question with a randomly chosen option order and
     * its matching voice clip. The id is deliberately preserved: the wrong
     * book, enabled flags and picker weights must not depend on the variant.
     */
    public synchronized Q variantOf(Q base) {
        if (base == null) {
            return null;
        }
        Q q = new Q();
        q.id = base.id;
        q.subject = base.subject;
        q.q = base.q;
        q.voice = base.voice;
        q.big = base.big;
        q.title = base.title;
        q.colors = base.colors;
        q.qcolor = base.qcolor;
        q.image = base.image;
        q.variants = base.variants;
        if (base.variants != null && !base.variants.isEmpty()) {
            Variant v = base.variants.get(rnd.nextInt(base.variants.size()));
            q.options = v.options;
            q.answer = v.answer;
            q.voiceId = v.voiceId;
        } else {
            q.options = base.options;
            q.answer = base.answer;
            q.voiceId = base.voiceId;
        }
        return q;
    }

    /** Read-only snapshot for the parent's 题库 page (includes never-asked items). */
    public synchronized List<Q> allQuestions() {
        return new ArrayList<>(all);
    }

    public synchronized Q pick(String[] subjects) {
        return pick(subjects, null, null);
    }

    public synchronized Q pick(String[] subjects, java.util.Map<String, Integer> weights) {
        return pick(subjects, weights, null);
    }

    private List<Q> buildPool(String[] subjects, java.util.Set<String> disabled,
                              boolean ignoreRecent) {
        List<Q> pool = new ArrayList<>();
        for (Q q : all) {
            if (!matches(q.subject, subjects)) {
                continue;
            }
            if (disabled != null && disabled.contains(q.id)) {
                continue;   // parent switched this question off in 题库
            }
            if (!ignoreRecent && recent.contains(q.id)) {
                continue;
            }
            pool.add(q);
        }
        return pool;
    }

    /**
     * Weighted picker. The caller supplies an absolute weight per question
     * (never-asked = 7, repeatedly wrong up to 25, mastered = 1), so both
     * new and hard questions surface early. Disabled ids are skipped; if a
     * parent disabled every available question we still fall back to the full
     * pool so the quiz never gets stuck.
     */
    public synchronized Q pick(String[] subjects, java.util.Map<String, Integer> weights,
                               java.util.Set<String> disabled) {
        if (all.isEmpty()) {
            return null;
        }
        List<Q> pool = buildPool(subjects, disabled, false);
        if (pool.isEmpty()) {
            recent.clear();
            pool = buildPool(subjects, disabled, true);
        }
        if (pool.isEmpty()) {
            // Nothing left enabled: keep the TV usable instead of deadlocking.
            pool = buildPool(subjects, null, true);
        }
        if (pool.isEmpty()) {
            pool.addAll(all);
        }
        Q chosen;
        if (weights != null && !weights.isEmpty()) {
            int[] w = new int[pool.size()];
            int total = 0;
            for (int i = 0; i < pool.size(); i++) {
                Integer c = weights.get(pool.get(i).id);
                w[i] = c == null ? 1 : Math.max(1, c);
                total += w[i];
            }
            int r = rnd.nextInt(total), acc = 0;
            chosen = pool.get(pool.size() - 1);
            for (int i = 0; i < pool.size(); i++) {
                acc += w[i];
                if (r < acc) {
                    chosen = pool.get(i);
                    break;
                }
            }
        } else {
            chosen = pool.get(rnd.nextInt(pool.size()));
        }
        recent.addLast(chosen.id);
        while (recent.size() > 12) {
            recent.removeFirst();
        }
        return chosen;
    }

    private static boolean matches(String cn, String[] subjects) {
        if (subjects == null || subjects.length == 0) {
            return true;
        }
        for (String s : subjects) {
            // 语文 is the pinyin/识字 group; "pinyin" is the legacy key kept
            // for existing parent settings, "chinese" is the new alias.
            boolean chinese = "语文".equals(cn) || "拼音".equals(cn);
            if (("math".equals(s) && "数学".equals(cn))
                    || ("english".equals(s) && "英语".equals(cn))
                    || (("pinyin".equals(s) || "chinese".equals(s)) && chinese)
                    || ("visual".equals(s) && "图形".equals(cn))) {
                return true;
            }
        }
        return false;
    }
}
