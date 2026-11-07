#include "device_identity.h"
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "esp_log.h"
#include "esp_random.h"
#include "mbedtls/pk.h"
#include "mbedtls/x509_crt.h"
#include "mbedtls/oid.h"
#include "psa/crypto.h"
#include "nvs.h"
#include "nvs_flash.h"

static esp_err_t generate_identity(const char *mac, device_identity_t *identity)
{
    unsigned bytes[6];
    if (sscanf(mac, "%2x:%2x:%2x:%2x:%2x:%2x", &bytes[0], &bytes[1], &bytes[2], &bytes[3], &bytes[4], &bytes[5]) != 6)
        return ESP_ERR_INVALID_ARG;
    char name[40], subject[48];
    snprintf(name, sizeof(name), "uwb-%02x%02x%02x%02x%02x%02x.local", bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
    snprintf(subject, sizeof(subject), "CN=%s", name);
    identity->certificate = calloc(2048, 1);
    identity->private_key = calloc(1024, 1);
    if (!identity->certificate || !identity->private_key) return ESP_ERR_NO_MEM;
    mbedtls_pk_context key;
    mbedtls_pk_init(&key);
    mbedtls_x509write_cert cert;
    mbedtls_x509write_crt_init(&cert);
    psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
    mbedtls_svc_key_id_t key_id = 0;
    int result = psa_crypto_init();
    if (result) goto done;
    psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attributes, 256);
    psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_EXPORT | PSA_KEY_USAGE_SIGN_HASH | PSA_KEY_USAGE_VERIFY_HASH);
    psa_set_key_algorithm(&attributes, PSA_ALG_ECDSA(PSA_ALG_SHA_256));
    if ((result = psa_generate_key(&attributes, &key_id))) goto done;
    if ((result = mbedtls_pk_copy_from_psa(key_id, &key))) goto done;
    mbedtls_x509write_crt_set_version(&cert, MBEDTLS_X509_CRT_VERSION_3);
    mbedtls_x509write_crt_set_md_alg(&cert, MBEDTLS_MD_SHA256);
    mbedtls_x509write_crt_set_subject_key(&cert, &key);
    mbedtls_x509write_crt_set_issuer_key(&cert, &key);
    unsigned char serial[16];
    esp_fill_random(serial, sizeof(serial));
    serial[0] = (serial[0] & 0x7f) | 1;
    mbedtls_x509_san_list san = {0};
    san.node.type = MBEDTLS_X509_SAN_DNS_NAME;
    san.node.san.unstructured_name.p = (unsigned char *)name;
    san.node.san.unstructured_name.len = strlen(name);
    mbedtls_asn1_sequence usage = {0};
    usage.buf.tag = MBEDTLS_ASN1_OID;
    usage.buf.p = (unsigned char *)MBEDTLS_OID_SERVER_AUTH;
    usage.buf.len = MBEDTLS_OID_SIZE(MBEDTLS_OID_SERVER_AUTH);
    if ((result = mbedtls_x509write_crt_set_serial_raw(&cert, serial, sizeof(serial)))) goto done;
    if ((result = mbedtls_x509write_crt_set_subject_name(&cert, subject))) goto done;
    if ((result = mbedtls_x509write_crt_set_issuer_name(&cert, subject))) goto done;
    // First boot may precede wall-clock synchronization; clients still check validity.
    if ((result = mbedtls_x509write_crt_set_validity(&cert, "20200101000000", "20491231235959"))) goto done;
    if ((result = mbedtls_x509write_crt_set_subject_alternative_name(&cert, &san))) goto done;
    if ((result = mbedtls_x509write_crt_set_basic_constraints(&cert, 0, -1))) goto done;
    if ((result = mbedtls_x509write_crt_set_key_usage(&cert, MBEDTLS_X509_KU_DIGITAL_SIGNATURE))) goto done;
    if ((result = mbedtls_x509write_crt_set_ext_key_usage(&cert, &usage))) goto done;
    if ((result = mbedtls_x509write_crt_pem(&cert, (unsigned char *)identity->certificate, 2048))) goto done;
    result = mbedtls_pk_write_key_pem(&key, (unsigned char *)identity->private_key, 1024);
done:
    mbedtls_x509write_crt_free(&cert);
    mbedtls_pk_free(&key);
    if (key_id) psa_destroy_key(key_id);
    psa_reset_key_attributes(&attributes);
    if (result) ESP_LOGE("identity", "HTTPS identity generation failed: %d", result);
    return result ? ESP_FAIL : ESP_OK;
}

static esp_err_t read_pem(nvs_handle_t handle, const char *key, size_t maximum, char **value)
{
    size_t size = 0;
    esp_err_t err = nvs_get_str(handle, key, NULL, &size);
    if (err != ESP_OK) return err;
    if (size < 32 || size > maximum) return ESP_ERR_INVALID_SIZE;
    *value = calloc(size + 1, 1);
    if (!*value) return ESP_ERR_NO_MEM;
    return nvs_get_str(handle, key, *value, &size);
}

void device_identity_free(device_identity_t *identity)
{
    if (!identity) return;
    free(identity->certificate);
    if (identity->private_key) {
        volatile char *key = identity->private_key;
        size_t length = strlen(identity->private_key);
        while (length--) *key++ = 0;
        free(identity->private_key);
    }
    memset(identity, 0, sizeof(*identity));
}

esp_err_t device_identity_load(const char *expected_mac, device_identity_t *identity)
{
    if (!expected_mac || !identity) return ESP_ERR_INVALID_ARG;
    memset(identity, 0, sizeof(*identity));
    esp_err_t err = nvs_flash_init_partition("identity");
    if (err != ESP_OK) return err; // Never erase an unreadable identity and silently replace trust.
    nvs_handle_t handle;
    err = nvs_open_from_partition("identity", "tls", NVS_READWRITE, &handle);
    if (err != ESP_OK) return err;
    char mac[18];
    size_t size = sizeof(mac);
    err = nvs_get_str(handle, "mac", mac, &size);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        size_t cert_size = 0, key_size = 0;
        if (nvs_get_str(handle, "cert", NULL, &cert_size) != ESP_ERR_NVS_NOT_FOUND ||
            nvs_get_str(handle, "key", NULL, &key_size) != ESP_ERR_NVS_NOT_FOUND) {
            err = ESP_ERR_INVALID_STATE;
        } else {
            err = generate_identity(expected_mac, identity);
            if (err == ESP_OK) err = nvs_set_str(handle, "cert", identity->certificate);
            if (err == ESP_OK) err = nvs_set_str(handle, "key", identity->private_key);
            if (err == ESP_OK) err = nvs_set_str(handle, "mac", expected_mac);
            if (err == ESP_OK) err = nvs_commit(handle);
        }
    } else {
        if (err == ESP_OK && strcmp(mac, expected_mac)) err = ESP_ERR_INVALID_STATE;
        if (err == ESP_OK) err = read_pem(handle, "cert", 4096, &identity->certificate);
        if (err == ESP_OK) err = read_pem(handle, "key", 2048, &identity->private_key);
    }
    nvs_close(handle);
    if (err != ESP_OK) device_identity_free(identity);
    return err;
}
