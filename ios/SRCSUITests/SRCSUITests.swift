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

    /// Scrolls the home list until [element] exists (List rows off screen are not in the hierarchy).
    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication, maxSwipes: Int = 8) -> Bool {
        if element.waitForExistence(timeout: 3) { return true }
        for _ in 0..<maxSwipes {
            app.swipeUp()
            if element.waitForExistence(timeout: 2) { return true }
        }
        return false
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

        // Go online (Home, offline state).
        let online = app.buttons["onlineButton"]
        XCTAssertTrue(online.waitForExistence(timeout: 10))
        online.tap()
        XCTAssertTrue(app.staticTexts["Online · not linked"].waitForExistence(timeout: 20))
        snap(app, "02-online")

        // Connect to the desktop test phone.
        let address = app.textFields["addressField"]
        XCTAssertTrue(scrollTo(address, in: app), "address field")
        address.tap()
        address.typeText("127.0.0.1:47710")
        app.buttons["connectButton"].tap()

        // Compare pairing codes.
        let match = app.alerts.buttons["Codes match"]
        XCTAssertTrue(match.waitForExistence(timeout: 30), "pairing dialog")
        snap(app, "03-pairing-code")
        match.tap()
        snap(app, "04a-home-connected")
        // Paired phones are listed on the Team tab.
        let team = app.tabBars.buttons["Team"]
        XCTAssertTrue(team.waitForExistence(timeout: 10))
        team.tap()
        let chat = app.buttons["chat-Desktop test phone"]
        _ = chat.waitForExistence(timeout: 20)
        XCTAssertTrue(scrollTo(chat, in: app), "paired phone listed")
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
