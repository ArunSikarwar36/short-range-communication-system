import SrcsCore
import SwiftUI

struct ChatScreen: View {
    @EnvironmentObject var model: AppModel
    let peerId: String
    let title: String
    @State private var messages: [MessageView] = []
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(messages, id: \.id) { m in
                            bubble(m).id(m.id)
                        }
                    }
                    .padding(12)
                }
                .onChange(of: messages.count) { _ in
                    if let last = messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }
            Divider()
            HStack(spacing: 8) {
                TextField("Message", text: $draft, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("messageField")
                Button {
                    model.controller.sendMessage(peerId: peerId, text: draft)
                    draft = ""
                } label: {
                    Image(systemName: "paperplane.fill")
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("sendButton")
            }
            .padding(10)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: reload)
        .onChange(of: model.snap?.messagesVersion ?? 0) { _ in reload() }
    }

    private func reload() {
        messages = model.controller.messages(conversationId: peerId)
        if messages.contains(where: { !$0.outgoing }) { model.controller.markRead(conversationId: peerId) }
    }

    private func bubble(_ m: MessageView) -> some View {
        HStack {
            if m.outgoing { Spacer(minLength: 40) }
            VStack(alignment: m.outgoing ? .trailing : .leading, spacing: 4) {
                Text(m.body)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(m.outgoing ? Color.accentColor.opacity(0.85) : Color(white: 0.2), in: RoundedRectangle(cornerRadius: 14))
                if !m.status.isEmpty {
                    Text(m.status).font(.caption2).foregroundStyle(.secondary)
                }
            }
            if !m.outgoing { Spacer(minLength: 40) }
        }
    }
}
