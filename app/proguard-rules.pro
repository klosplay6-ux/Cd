# JNI köprüsü Aşama 2'de eklenecek; native metot sınıfları burada korunacak.
-keepclasseswithmembernames class * {
    native <methods>;
}
