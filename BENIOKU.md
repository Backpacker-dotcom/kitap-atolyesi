# Kitap Atölyesi

Android telefon ve Huawei tablette (HarmonyOS 2/3/4) çalışan e-kitap **okuyucu** ve **oluşturucu**.

- **Okur:** EPUB, PDF, TXT. Kaldığın yer, içindekiler, yazı boyutu, satır aralığı, Beyaz/Sepya/Gece teması.
- **Yazar:** Bölüm bölüm kitap yaz, kapak ve görsel ekle, **EPUB** olarak kaydet ya da kitaplığına ekleyip oku.
- **Dönüştürür:** Word (.docx; başlıklar bölüm olur, resimler korunur), PDF (metin), TXT.
- **Yapay zekâ (Gemini):** yazım düzeltme, akıcılaştırma, devam ve başlık önerisi, serbest istek.
- **Eşitleme (Firebase, ücretsiz plan):** kitaplar, kaldığın sayfa ve yazdıkların telefonla tablet arasında. Google Play Hizmetleri gerekmez.

## Kurulum
GitHub'da **Releases** bölümündeki en son `KitapAtolyesi-v1.0.X.apk` dosyasını telefona/tablete indirip kur.
Yeni sürümler eskisinin üzerine kurulur, veriler korunur.

## Firebase eşitleme (bir kerelik)
1. console.firebase.google.com → **Proje ekle** (ör. `kitap-atolyesi`). Analytics gerekmez.
2. **Build → Authentication → Get started → Sign-in method → Email/Password → Enable**.
3. **Build → Firestore Database → Create database** → konum `eur3 (europe-west)` → **Production mode**.
4. Firestore → **Rules** sekmesi: uygulamadaki *Ayarlar → Firestore güvenlik kurallarını kopyala* ile kopyaladığını yapıştır → **Publish**.
5. **Proje ayarları (⚙)** → *Web API Key* ve *Project ID*'yi uygulamadaki Ayarlar'a yaz.
6. Telefonda **Hesap oluştur**, tablette aynı e-posta/şifreyle **Giriş yap**.

## Yazım işaretleri (bölüm editörü)
`**kalın**`, `*italik*`, satır başında `# Ara başlık`, `***` sahne arası, `![](images/...)` görsel (düğmeyle eklenir).
