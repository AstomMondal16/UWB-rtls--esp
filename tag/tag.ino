#include <Arduino.h>
#include <SPI.h>
#include <DW1000.h>
#include <DW1000Ranging.h>

// =====================================================
// ESP32 -> DW1000
// SAME PCB PIN CONNECTION AS ANCHOR
// =====================================================
#define UWB_PIN_SCK   5
#define UWB_PIN_MISO  6
#define UWB_PIN_MOSI  7
#define UWB_PIN_CS    15
#define UWB_PIN_IRQ   4
#define UWB_PIN_RST   16

// =====================================================
// TAG EUI
// MUST BE DIFFERENT FROM ANCHOR EUI
//
// Anchor:
// 84:00:5B:D5:A9:9A:E2:9C
//
// Tag:
// 7D:00:22:EA:82:60:3B:9C
// =====================================================
char TAG_EUI[] = "7D:00:22:EA:82:60:3B:9C";

// -----------------------------------------------------
// Callbacks
// -----------------------------------------------------
void newRange();
void newDevice(DW1000Device *device);
void inactiveDevice(DW1000Device *device);

// =====================================================
// SETUP
// =====================================================

void setup()
{
    Serial.begin(115200);
    delay(3000);

    Serial.println();
    Serial.println("======================================");
    Serial.println("DW1000 UWB TAG");
    Serial.println("======================================");

    Serial.println();
    Serial.println("ESP32 -> DW1000 PIN CONNECTION");
    Serial.println("--------------------------------------");

    Serial.printf("SCK  : GPIO %d\n", UWB_PIN_SCK);
    Serial.printf("MISO : GPIO %d\n", UWB_PIN_MISO);
    Serial.printf("MOSI : GPIO %d\n", UWB_PIN_MOSI);
    Serial.printf("CS   : GPIO %d\n", UWB_PIN_CS);
    Serial.printf("IRQ  : GPIO %d\n", UWB_PIN_IRQ);
    Serial.printf("RST  : GPIO %d\n", UWB_PIN_RST);

    // =================================================
    // SPI INITIALIZATION
    // =================================================

    Serial.println();
    Serial.println("[1] Configuring SPI...");

    SPI.begin(
        UWB_PIN_SCK,
        UWB_PIN_MISO,
        UWB_PIN_MOSI
    );

    Serial.println("[2] SPI started successfully.");

    delay(100);

    // =================================================
    // DW1000 INITIALIZATION
    // =================================================

    Serial.println("[3] Initializing DW1000 communication...");

    DW1000Ranging.initCommunication(
        UWB_PIN_RST,
        UWB_PIN_CS,
        UWB_PIN_IRQ
    );

    Serial.println("[4] DW1000 communication initialized.");

    // =================================================
    // CALLBACK REGISTRATION
    // =================================================

    Serial.println("[5] Registering callbacks...");

    DW1000Ranging.attachNewRange(newRange);
    DW1000Ranging.attachNewDevice(newDevice);
    DW1000Ranging.attachInactiveDevice(inactiveDevice);

    Serial.println("[6] Callbacks registered.");

    // =================================================
    // TAG INFORMATION
    // =================================================

    Serial.println();

    Serial.print("[7] TAG EUI = ");
    Serial.println(TAG_EUI);

    Serial.println("[8] Starting TAG mode...");

    // =================================================
    // START DW1000 AS TAG
    //
    // IMPORTANT:
    // Radio mode MUST MATCH ANCHOR.
    //
    // Anchor:
    // DW1000.MODE_LONGDATA_RANGE_ACCURACY
    //
    // Tag:
    // DW1000.MODE_LONGDATA_RANGE_ACCURACY
    // =================================================

    DW1000Ranging.startAsTag(
        TAG_EUI,
        DW1000.MODE_LONGDATA_RANGE_ACCURACY
    );

    Serial.println("[9] TAG initialized successfully.");

    Serial.println();
    Serial.println("Waiting for anchor...");
}


// =====================================================
// MAIN LOOP
// =====================================================

void loop()
{
    // REQUIRED:
    // This runs the DW1000 ranging state machine
    DW1000Ranging.loop();

    // -------------------------------------------------
    // Heartbeat every 2 seconds
    // -------------------------------------------------

    static uint32_t previous = 0;

    if (millis() - previous >= 2000)
    {
        previous = millis();

        Serial.println(
            "[TAG] Running - searching for anchor..."
        );
    }
}


// =====================================================
// RANGE CALLBACK
// Called whenever a new distance measurement is ready
// =====================================================

void newRange()
{
    DW1000Device *device =
        DW1000Ranging.getDistantDevice();

    if (device == nullptr)
    {
        return;
    }

    Serial.println();
    Serial.println("========= RANGE =========");

    // -------------------------------------------------
    // Anchor short address
    // -------------------------------------------------

    Serial.print("ANCHOR address : 0x");

    Serial.println(
        device->getShortAddress(),
        HEX
    );

    // -------------------------------------------------
    // Distance
    // -------------------------------------------------

    Serial.print("Distance       : ");

    Serial.print(
        device->getRange(),
        3
    );

    Serial.println(" m");

    // -------------------------------------------------
    // Received signal power
    // -------------------------------------------------

    Serial.print("RX power       : ");

    Serial.print(
        device->getRXPower(),
        2
    );

    Serial.println(" dBm");

    Serial.println("=========================");
}


// =====================================================
// NEW DEVICE CALLBACK
//
// On TAG this normally means that an ANCHOR has
// been discovered.
// =====================================================

void newDevice(DW1000Device *device)
{
    if (device == nullptr)
    {
        return;
    }

    Serial.println();
    Serial.println("*** NEW ANCHOR DETECTED ***");

    Serial.print("Short address: 0x");

    Serial.println(
        device->getShortAddress(),
        HEX
    );
}


// =====================================================
// INACTIVE DEVICE CALLBACK
//
// Called when an anchor is no longer responding.
// =====================================================

void inactiveDevice(DW1000Device *device)
{
    if (device == nullptr)
    {
        return;
    }

    Serial.println();
    Serial.println("*** ANCHOR INACTIVE ***");

    Serial.print("Short address: 0x");

    Serial.println(
        device->getShortAddress(),
        HEX
    );
}