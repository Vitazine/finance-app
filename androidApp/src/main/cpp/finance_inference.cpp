#include <jni.h>
#include <chrono>
#include <algorithm>
#include <atomic>
#include <memory>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct State {
    std::atomic<bool> cancel{false};
    llama_model * model = nullptr;
    std::atomic<int64_t> first_us{0}, total_us{0}, output_tokens{0};
    ~State() { if (model) llama_model_free(model); }
};
State * state(jlong h) { return reinterpret_cast<State *>(h); }
bool abort_decode(void * p) { return static_cast<State *>(p)->cancel.load(); }
bool progress(float, void * p) { return !abort_decode(p); }
void quiet(ggml_log_level, const char *, void *) {}
std::string bytes(JNIEnv * e, jbyteArray a) {
    if (!a) return {};
    std::string result(e->GetArrayLength(a), '\0');
    e->GetByteArrayRegion(a, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_app_finance_android_ai_NativeBridge_create(JNIEnv *, jobject) {
    try { llama_log_set(quiet, nullptr); llama_backend_init(); return reinterpret_cast<jlong>(new State()); }
    catch (...) { return 0; }
}
extern "C" JNIEXPORT jboolean JNICALL Java_app_finance_android_ai_NativeBridge_load(JNIEnv * e, jobject, jlong h, jbyteArray path) {
    try {
        auto s = state(h); if (!s || s->model || s->cancel.load()) return false;
        auto p = llama_model_default_params(); p.n_gpu_layers = 0; p.load_mode = LLAMA_LOAD_MODE_MMAP;
        p.progress_callback = progress; p.progress_callback_user_data = s;
        auto name = bytes(e, path);
        if (name.find('\0') != std::string::npos) return false;
        s->model = llama_model_load_from_file(name.c_str(), p);
        return s->model != nullptr && !s->cancel.load();
    } catch (...) { return false; }
}
extern "C" JNIEXPORT void JNICALL Java_app_finance_android_ai_NativeBridge_cancel(JNIEnv *, jobject, jlong h) {
    if (h) state(h)->cancel.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_app_finance_android_ai_NativeBridge_close(JNIEnv *, jobject, jlong h) { delete state(h); }
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_finance_android_ai_NativeBridge_generate(JNIEnv * e, jobject, jlong h, jbyteArray input, jint limit) {
    try {
        const auto start = std::chrono::steady_clock::now();
        auto micros = [&]() { return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count(); };
        auto s = state(h); if (!s || !s->model || s->cancel.load() || limit < 1 || limit > 256) return nullptr;
        s->first_us.store(0); s->total_us.store(0); s->output_tokens.store(0);
        auto prompt = bytes(e, input); if (prompt.size() > 32768) return nullptr;
        const auto vocab = llama_model_get_vocab(s->model);
        std::vector<llama_token> tokens(2048);
        auto n = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), false, true);
        if (n < 1 || n + limit > 2048) return nullptr;
        tokens.resize(n);
        auto p = llama_context_default_params(); p.n_ctx = 2048; p.n_batch = 256; p.n_ubatch = 128;
        p.n_threads = 4; p.n_threads_batch = 4; p.no_perf = true;
        p.abort_callback = abort_decode; p.abort_callback_data = s;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(s->model, p), llama_free);
        if (!ctx) return nullptr;
        for (int i = 0; i < n; i += 256) {
            if (s->cancel.load() || llama_decode(ctx.get(), llama_batch_get_one(tokens.data() + i, std::min(256, n - i))) != 0) return nullptr;
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::string result;
        bool ended = false;
        for (int i = 0; i < limit && !s->cancel.load(); ++i) {
            llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (i == 0) s->first_us.store(micros());
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            char piece[1024]; int count = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (count < 0 || result.size() + count > 32768) return nullptr;
            result.append(piece, count); s->output_tokens.fetch_add(1);
            if (llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) != 0) return nullptr;
        }
        s->total_us.store(micros());
        if (!ended || s->cancel.load()) return nullptr;
        auto out = e->NewByteArray(result.size());
        if (out) e->SetByteArrayRegion(out, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return out;
    } catch (...) { return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_finance_android_ai_NativeBridge_timings(JNIEnv * e, jobject, jlong h) {
    if (!h) return nullptr;
    auto s = state(h); jlong values[] = {s->first_us.load(), s->total_us.load(), s->output_tokens.load()};
    auto result = e->NewLongArray(3); if (result) e->SetLongArrayRegion(result, 0, 3, values); return result;
}
