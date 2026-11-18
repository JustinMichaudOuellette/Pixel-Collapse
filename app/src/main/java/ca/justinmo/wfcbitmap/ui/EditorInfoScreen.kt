package ca.justinmo.wfcbitmap.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ca.justinmo.wfcbitmap.R

private const val CREDIT_URL = "https://www.justinmo.ca"
private const val PROJECT_URL = "https://github.com/JustinMichaudOuellette/Pixel-Collapse"
private const val AUTHOR = "Justin Michaud-Ouellette"

/**
 * The app introducing itself: the icon and name, its version, where it came from, what the solver does, and
 * a way to pass it on.
 *
 * Modelled on the about screen of the author's other app: a centred identity block, a History section, and a
 * short list of the ideas the app is built on, before the detailed explanations of the editor's controls.
 * The version is read from the package rather than written here, so a release only changes it in one place.
 */
@Composable
internal fun EditorInfoScreen(
  open: Boolean,
  onClose: () -> Unit,
  onOpenTutorial: () -> Unit = {},
) {
  if (!open) return

  val context = LocalContext.current
  // Layoutlib's mock context throws on packageManager calls, so the IDE preview uses a fixed version.
  val isInspectionMode = LocalInspectionMode.current
  val version = remember(context, isInspectionMode) {
    if (isInspectionMode) {
      "0.0.1"
    } else {
      runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
      }.getOrNull().orEmpty()
    }
  }
  val credit = buildAnnotatedString {
    append("© 2026 ")
    withLink(
      LinkAnnotation.Url(
        CREDIT_URL,
        TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
      ),
    ) {
      append(AUTHOR)
    }
  }

  InfoScreen(title = "About", onClose = onClose) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier
          .fillMaxWidth()
          .height(148.dp),
        tint = MENU_TEXT_COLOR,
      )
      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Thin,
        color = MENU_TEXT_COLOR,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
      if (version.isNotEmpty()) {
        Text(
          text = "Version $version",
          style = MaterialTheme.typography.bodyLarge,
          color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
      Text(
        text = credit,
        style = MaterialTheme.typography.labelMedium,
        color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
      )
      FilledTonalButton(
        onClick = {
          val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, PROJECT_URL)
            type = "text/plain"
          }
          runCatching { context.startActivity(Intent.createChooser(send, "Share App")) }
        },
        colors = ButtonDefaults.filledTonalButtonColors(
          containerColor = MENU_TEXT_COLOR.copy(alpha = 0.14f),
          contentColor = MENU_TEXT_COLOR,
        ),
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 20.dp),
      ) {
        Icon(Icons.Outlined.Share, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Share")
      }
      OutlinedButton(
        onClick = onOpenTutorial,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MENU_TEXT_COLOR),
        border = BorderStroke(1.dp, MENU_DIVIDER_COLOR),
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 10.dp),
      ) {
        Text("Show tutorial")
      }
    }

    InfoSection(
      body = "I started experimenting with wave function collapse as part of my pen plotting work. " +
          "I had a lot of fun creating a pseudo electric schematic generator. " +
          "I found the WFC algorithm to be particularly interesting so I decided to explore a bitmap based implementation. " +
          "Mobile felt like an interesting platform for it, not only because I am a seasoned mobile app developer but also because the touch interface makes it enjoyable. " +
          "The resulting app is a tool that I created for me to play with and explore. " +
          "I hope you enjoy it as much as I enjoyed creating it." +
          "\n\n— jmo",
    )
  }
}

/**
 * The three ideas as a two-column grid: the name of each, and the one sentence that says what it means.
 *
 * The title column is measured from the widest title rather than fixed, so every description starts on the
 * same line while a longer title cannot take the descriptions' whole row. That is what makes the three read
 * as one table instead of three unrelated paragraphs.
 */
@Composable
private fun StepGrid(rows: List<Pair<String, String>>) {
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
  val titles = rows.map { it.first }
  val widest = remember(titles, titleStyle, density) {
    titles.maxOfOrNull { measurer.measure(it, titleStyle).size.width } ?: 0
  }
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val titleWidth = minOf(with(density) { widest.toDp() }, maxWidth * 0.45f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      rows.forEach { (title, description) ->
        StepRow(title, description, titleWidth, titleStyle)
      }
    }
  }
}

/** One row of [StepGrid]: a title in the measured column, and its description beside it. */
@Composable
private fun StepRow(
  title: String,
  description: String,
  titleWidth: Dp,
  titleStyle: TextStyle,
) {
  Row(verticalAlignment = Alignment.Top) {
    Text(
      text = title,
      style = titleStyle,
      color = MENU_TEXT_COLOR,
      modifier = Modifier.width(titleWidth),
    )
    Spacer(Modifier.width(12.dp))
    Text(
      text = description,
      style = MaterialTheme.typography.bodyMedium,
      color = MENU_TEXT_COLOR.copy(alpha = 0.72f),
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * One titled block of the explanation: a heading, a paragraph, and an optional illustration underneath.
 *
 * Sections and not one long paragraph: each one answers a question the editor raises on its own -- why the
 * picture is not a copy, what the sizes mean, where the files went -- so that a reader who only wants one
 * of those answers can find it without reading the rest.
 */
@Composable
internal fun InfoSection(
  title: String? = null,
  body: String,
  content: @Composable () -> Unit = {},
) {
  Spacer(Modifier.height(22.dp))
  if (title != null) {
    Text(
      text = title,
      color = MENU_TEXT_COLOR,
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(5.dp))
  }
  Text(
    text = body,
    color = MENU_TEXT_COLOR.copy(alpha = 0.72f),
    fontSize = 13.sp,
    lineHeight = 19.sp,
  )
  content()
}

/**
 * The explanation as a full screen: a title bar with a close button, and a scrolling body under it.
 *
 * A plain composable in the same window as the editor, so opening it never costs the drawing its state --
 * no collapse is repeated, and nothing on the editor is rebuilt when it closes.
 */
@Composable
internal fun InfoScreen(
  title: String,
  onClose: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = PANEL_BACKGROUND) {
      Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
          Text(
            text = title,
            color = MENU_TEXT_COLOR,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
          )
          IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = MENU_TEXT_COLOR,
            )
          }
        }
        HorizontalDivider(color = MENU_DIVIDER_COLOR)
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
          content = content,
        )
      }
    }
  }
}
