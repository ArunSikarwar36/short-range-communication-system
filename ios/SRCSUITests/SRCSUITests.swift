import XCTest

/// End-to-end check run by CI on an iPhone simulator against the desktop test phone
/// (gradlew :core:demoPeer, TCP 47710), which confirms pairing automatically and echoes texts.
final class SRCSUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func snap(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    func testPairAndMessageWithDesktopTestPhone() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-resetForUITest"]
        app.launch()

        // Onboarding.
        let nameField = app.textFields["nameField"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 20))
        snap(app, "01-onboarding")
        nameField.tap()
        nameField.typeText("CI iPhone")
        app.buttons["getStarted"].tap()

        // Go online.
        let online = app.buttons["onlineButton"]
        XCTAssertTrue(online.waitForExistence(timeout: 10))
        online.tap()
        XCTAssertTrue(app.staticTexts["Online · not linked"].waitForExistence(timeout: 20))
        snap(app, "02-online")

        // Connect to the desktop test phone.
        let address = app.textFields["addressField"]
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        address.tap()
        address.typeText("127.0.0.1:47710")
        app.buttons["connectButton"].tap()

        // Compare pairing codes.
        let match = app.alerts.buttons["Codes match"]
        XCTAssertTrue(match.waitForExistence(timeout: 30), "pairing dialog")
        snap(app, "03-pairing-code")
        match.tap()
        // The list has scrolled to the address field; the paired phone's row appears just below it.
        let chat = app.buttons["chat-Desktop test phone"]
        if !chat.waitForExistence(timeout: 30) {
            app.swipeUp()
        }
        XCTAssertTrue(chat.waitForExistence(timeout: 10), "paired phone listed")
        snap(app, "04-paired")

        // Message and echo.
        chat.tap()
        let field = app.textFields["messageField"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("hello from the iPhone simulator")
        app.buttons["sendButton"].tap()
        XCTAssertTrue(app.staticTexts["Echo: hello from the iPhone simulator"].waitForExistence(timeout: 30), "echo reply")
        XCTAssertTrue(app.staticTexts["Delivered"].waitForExistence(timeout: 10) || app.staticTexts["Read"].exists)
        snap(app, "05-chat")
    }
}
