package org.homio.addon.mail;

import com.pivovarit.function.ThrowingBiConsumer;
import com.pivovarit.function.ThrowingFunction;
import com.pivovarit.function.ThrowingPredicate;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMultipart;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.homio.api.Context;
import org.homio.api.state.DecimalType;
import org.homio.api.state.RawType;
import org.homio.api.state.State;
import org.homio.api.workspace.WorkspaceBlock;
import org.homio.api.workspace.scratch.MenuBlock;
import org.homio.api.workspace.scratch.Scratch3Block;
import org.homio.api.workspace.scratch.Scratch3ExtensionBlocks;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

@Log4j2
@Component
public class Scratch3MailBlocks extends Scratch3ExtensionBlocks {

  private final MenuBlock.ServerMenuBlock mailMenu;
  private final MenuBlock.StaticMenuBlock<MailService.MailCountFilter> mailCountFilterMenu;

  private final Scratch3Block attachFileCommand;

  private final Scratch3Block mailCountReporter;
  private final Scratch3Block whenGotMailHat;
  private Integer lastMessageCount;

  public Scratch3MailBlocks(Context context, MailEntrypoint mailEntrypoint) {
    super("#8F4D77", context, mailEntrypoint, null);
    setParent(ScratchParent.communication);

    // Menu
    this.mailMenu = menuServerItems("mailEntity", MailEntity.class, "Select Mail");
    this.mailCountFilterMenu = menuStatic("mailCountFilter", MailService.MailCountFilter.class, MailService.MailCountFilter.total);

    // Hats
    this.whenGotMailHat = withMail(blockHat(20, "get_mail",
      "Get mail (subject [SUBJECT]), (from [FROM]) of [MAIL]", this::whenGotMailHat));
    this.whenGotMailHat.addArgument("SUBJECT", "-");
    this.whenGotMailHat.addArgument("FROM", "receiver@mail.com");
    this.whenGotMailHat.appendSpace();

    // reporters
    this.mailCountReporter = withMail(blockReporter(40, "mails_count",
      "Get [FILTER] mails of [MAIL] in folder [FOLDER]", this::getMailCountReporter));
    this.mailCountReporter.addArgument("FILTER", this.mailCountFilterMenu);
    this.mailCountReporter.addArgument("FOLDER", "INBOX");
    this.mailCountReporter.appendSpace();

    // commands
    Scratch3Block sendMailCommand = withMail(blockCommand(100, "send_mail",
      "Send mail [TITLE] to [RECIPIENTS] of [MAIL] with body [BODY]", this::sendMailCommand));
    sendMailCommand.addArgument("TITLE", "title");
    sendMailCommand.addArgument("RECIPIENTS", "receiver@mail.com");
    sendMailCommand.addArgument("BODY", "<b>body</b>");

    this.attachFileCommand = blockCommand(130, MailApplyHandler.update_add_file.name(),
      "Attach file[VALUE]", this::skipExpression);
    this.attachFileCommand.addArgument(VALUE, "file");
  }

  private State getMailCountReporter(WorkspaceBlock workspaceBlock) {
    MailEntity mailEntity = getMailEntity(workspaceBlock);
    var mailCountFilter = workspaceBlock.getMenuValue("FILTER", this.mailCountFilterMenu);
    String folder = workspaceBlock.getInputString("FOLDER");
    return new DecimalType(mailEntity.getService().getMailCount(mailCountFilter, folder));
  }

  private void whenGotMailHat(WorkspaceBlock workspaceBlock) {
    String subject = workspaceBlock.getInputString("SUBJECT");
    String from = workspaceBlock.getInputString("FROM");
    handleNextWhenGotNewMessages(workspaceBlock, message -> {
      // test from address
      if (!from.isEmpty() && !from.equals("-")) {
        boolean passMessage = false;
        for (Address fromAddress : message.getFrom()) {
          if (fromAddress.toString().contains(from)) {
            passMessage = true;
          }
        }
        if (!passMessage) {
          return false;
        }
      }

      return subject.isEmpty() || subject.equals("-") ||
             Objects.toString(message.getSubject(), "").contains(subject);
    });
  }

  private void handleNextWhenGotNewMessages(WorkspaceBlock workspaceBlock,
                                            ThrowingPredicate<Message, Exception> acceptMessage) {
    handleHat(workspaceBlock, mailbox -> {
      // search for new messages
      int messageCount = mailbox.getMessageCount();
      if (lastMessageCount != null && messageCount > lastMessageCount) {
        for (int i = lastMessageCount; i < messageCount; i++) {
          Message message = mailbox.getMessage(i + 1);
          if (acceptMessage.test(message)) {
            lastMessageCount = messageCount;
            return message;
          }
        }
      }
      lastMessageCount = messageCount;
      return null;
    });
  }

  private void handleHat(WorkspaceBlock workspaceBlock, ThrowingFunction<Folder, Message, Exception> handler) {
    workspaceBlock.handleNext(next -> {
      MailEntity mailEntity = getMailEntity(workspaceBlock);

      mailEntity.getService().registerHandler(workspaceBlock.getBlockId(), store -> {
        try (Folder mailbox = store.getFolder(mailEntity.getDefFolder())) {
          mailbox.open(Folder.READ_ONLY);
          Message message = handler.apply(mailbox);
          if (message != null) {
            String text = null;
            if (message.isMimeType("text/plain")) {
              text = message.getContent().toString();
            } else if (message.isMimeType("multipart/*")) {
              MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
              text = getTextFromMimeMultipart(mimeMultipart);
            }
            workspaceBlock.setValue(new RawType(Objects.toString(text, "").getBytes(),
              MediaType.TEXT_PLAIN_VALUE, message.getSubject()));
            next.handle();
          }
        } catch (Exception ex) {
          log.error("Error when fetching mails from server", ex);
        }
      });

      workspaceBlock.onRelease(() -> mailEntity.getService().releaseHandler(workspaceBlock.getBlockId()));
    });
  }

  private MailEntity getMailEntity(WorkspaceBlock workspaceBlock) {
    return workspaceBlock.getMenuValueEntityRequired("MAIL", this.mailMenu);
  }

  private void skipExpression(WorkspaceBlock ignore) {
    // skip expression
  }

  @SneakyThrows
  private void sendMailCommand(WorkspaceBlock workspaceBlock) {
    MailEntity mailEntity = getMailEntity(workspaceBlock);
    MailBuilder mailBuilder = new MailBuilder(mailEntity,
      workspaceBlock.getInputString("TITLE"),
      workspaceBlock.getInputString("BODY"),
      workspaceBlock.getInputString("RECIPIENTS"));
    applyParentBlocks(mailBuilder, workspaceBlock.getParent());

    mailBuilder.sendMail();
  }

  @SneakyThrows
  private void applyParentBlocks(MailBuilder mailBuilder, WorkspaceBlock parent) {
    if (parent == null || !parent.getBlockId().startsWith("mail_update_")) {
      return;
    }
    applyParentBlocks(mailBuilder, parent.getParent());
    MailApplyHandler.valueOf(parent.getOpcode()).applyFn.accept(parent, mailBuilder);
  }

  private Scratch3Block withMail(Scratch3Block scratch3Block) {
    scratch3Block.addArgument("MAIL", this.mailMenu);
    return scratch3Block;
  }

  @SneakyThrows
  private String getTextFromMimeMultipart(
    MimeMultipart mimeMultipart)  {
    StringBuilder result = new StringBuilder();
    int count = mimeMultipart.getCount();
    for (int i = 0; i < count; i++) {
      BodyPart bodyPart = mimeMultipart.getBodyPart(i);
      if (bodyPart.isMimeType("text/plain")) {
        result.append("\n").append(bodyPart.getContent());
        break; // without break same text appears twice in my tests
      } else if (bodyPart.isMimeType("text/html")) {
        String html = (String) bodyPart.getContent();
        result.append("\n").append(Jsoup.parse(html).text());
      } else if (bodyPart.getContent() instanceof MimeMultipart) {
        result.append(getTextFromMimeMultipart((MimeMultipart) bodyPart.getContent()));
      }
    }
    return result.toString();
  }

  @AllArgsConstructor
  private enum MailApplyHandler {
    update_add_file((workspaceBlock, mailBuilder) -> {
      Object input = workspaceBlock.getInput(VALUE, true);
      if (input instanceof String mediaURL) {
        if (mediaURL.startsWith("http")) {
          mailBuilder.withURLAttachment(mediaURL);
        } else {
          Path path = Paths.get(mediaURL);
          if (Files.isRegularFile(path)) {
            mailBuilder.withFileAttachment(mediaURL);
          } else {
            writeAsByteArray(workspaceBlock, mailBuilder);
          }
        }
      } else {
        writeAsByteArray(workspaceBlock, mailBuilder);
      }
    });

    private final ThrowingBiConsumer<WorkspaceBlock, MailBuilder, Exception> applyFn;

    private static void writeAsByteArray(WorkspaceBlock workspaceBlock, MailBuilder mailBuilder) throws IOException {
      Path attachment = Files.createTempFile("mail_attachment_" + workspaceBlock.hashCode(), "tmp");
      Files.write(attachment, workspaceBlock.getInputByteArray(VALUE));
      mailBuilder.withFileAttachment(attachment.toString());
    }
  }
}
