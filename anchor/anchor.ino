#include <Arduino.h>
#include <SPI.h>
#include <DW1000.h>
#include <DW1000Ranging.h>

// =====================================================
// ESP32 -> DW1000
// SAME PCB PIN CONNECTION
// =====================================================
#define UWB_PIN_SCK   5
#define UWB_PIN_MISO  6
#define UWB_PIN_MOSI  7
#define UWB_PIN_CS    15
#define UWB_PIN_IRQ   4
#define UWB_PIN_RST   16

// Must be DIFFERENT from TAG EUI
char ANCHOR_EUI[] = "84:00:5B:D5:A9:9A:E2:9C";

// -----------------------------------------------------
// Callbacks
// -----------------------------------------------------
void newRange();
void newDevice(DW1000Device *device);
void inactiveDevice(DW1000Device *device);

void setup()
{
    Serial.begin(115200);
    delay(3000);

    Serial.println();
    Serial.println("======================================");
    Serial.println("DW1000 UWB ANCHOR");
    Serial.println("======================================");

    Serial.printf("SCK  : GPIO %d\n", UWB_PIN_SCK);
    Serial.printf("MISO : GPIO %d\n", UWB_PIN_MISO);
    Serial.printf("MOSI : GPIO %d\n", UWB_PIN_MOSI);
    Serial.printf("CS   : GPIO %d\n", UWB_PIN_CS);
    Serial.printf("IRQ  : GPIO %d\n", UWB_PIN_IRQ);
    Serial.printf("RST  : GPIO %d\n", UWB_PIN_RST);

    Serial.println();
    Serial.println("[1] Configuring SPI...");

    SPI.begin(
        UWB_PIN_SCK,
        UWB_PIN_MISO,
        UWB_PIN_MOSI
    );

    Serial.println("[2] SPI started successfully.");

    delay(100);

    Serial.println("[3] Initializing DW1000 communication...");

    DW1000Ranging.initCommunication(
        UWB_PIN_RST,
        UWB_PIN_CS,
        UWB_PIN_IRQ
    );

    Serial.println("[4] DW1000 communication initialized.");

    // -------------------------------------------------
    // Register callbacks
    // -------------------------------------------------

    Serial.println("[5] Registering callbacks...");

    DW1000Ranging.attachNewRange(newRange);
    DW1000Ranging.attachNewDevice(newDevice);
    DW1000Ranging.attachInactiveDevice(inactiveDevice);

    Serial.println("[6] Callbacks registered.");

    Serial.print("[7] Anchor EUI = ");
    Serial.println(ANCHOR_EUI);

    Serial.println("[8] Starting ANCHOR mode...");

    // IMPORTANT:
    // Must use the same mode as your TAG.
    DW1000Ranging.startAsAnchor(
        ANCHOR_EUI,
        DW1000.MODE_LONGDATA_RANGE_ACCURACY
    );

    Serial.println("[9] ANCHOR initialized successfully.");
    Serial.println();
    Serial.println("Waiting for tag...");
}

void loop()
{
    // Required for DW1000 ranging state machine
    DW1000Ranging.loop();

    // Heartbeat
    static uint32_t previous = 0;

    if (millis() - previous >= 2000)
    {
        previous = millis();

        Serial.println(
            "[ANCHOR] Running - waiting for ranging packet..."
        );
    }
}

// =====================================================
// RANGE CALLBACK
// =====================================================

void newRange()
{
    DW1000Device *device =
        DW1000Ranging.getDistantDevice();

    if (device == nullptr)
        return;

    Serial.println();
    Serial.println("========= RANGE =========");

    Serial.print("TAG address : 0x");
    Serial.println(
        device->getShortAddress(),
        HEX
    );

    Serial.print("Distance    : ");
    Serial.print(
        device->getRange(),
        3
    );
    Serial.println(" m");

    Serial.print("RX power    : ");
    Serial.print(
        device->getRXPower(),
        2
    );
    Serial.println(" dBm");

    Serial.println("=========================");
}

// =====================================================
// NEW DEVICE CALLBACK
// =====================================================

void newDevice(DW1000Device *device)
{
    if (device == nullptr)
        return;

    Serial.println();
    Serial.println("*** NEW TAG DETECTED ***");

    Serial.print("Short address: 0x");
    Serial.println(
        device->getShortAddress(),
        HEX
    );
}

// =====================================================
// INACTIVE DEVICE CALLBACK
// =====================================================

void inactiveDevice(DW1000Device *device)
{
    if (device == nullptr)
        return;

    Serial.println();
    Serial.println("*** TAG INACTIVE ***");

    Serial.print("Short address: 0x");
    Serial.println(
        device->getShortAddress(),
        HEX
    );
}