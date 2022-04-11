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

$(() => {
  $('.select-text').on('click', selectText);
});
