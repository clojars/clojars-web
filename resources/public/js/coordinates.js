const selectText = (event) => {
  if (window.getSelection) {
    if (window.getSelection().type != 'Range') {
      const range = document.createRange();
      range.selectNodeContents(event.currentTarget);
      window.getSelection().removeAllRanges();
      window.getSelection().addRange(range);
    }
  }
};

const copyCoordinates = (event) => {
  const button = $(event['target']);
  const container = button.parent().parent()[0];
  const coords = $(container).children()[0];
  navigator.clipboard.writeText(coords.innerText);
  button.html('Copied!');
  setTimeout(() => button.html('Copy'), 1000);
};

$(() => {
  $('.select-text').on('click', selectText);
  $('button.copy-coordinates').on('click', copyCoordinates);
});
