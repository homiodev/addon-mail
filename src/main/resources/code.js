class MailWidget extends HTMLElement {
    searchQuery = '';
    mails = [];
    currentPage = 1;
    itemsPerPage = 10;
    sortBy = 'date';
    sortDir = 'desc';
    currentFilter = 'all';
    isComposeOpen = false;
    attachments = [];

    setContext(widget) {
        this.widget = widget;
        widget.dataWarehouse.subscribe(data => {
            this.mails = data || [];
            if (!this.viewingMail && !this.isComposeOpen) {
                this.render();
            }
        });
    }

    render() {
        if (this.isComposeOpen) {
            this.showSendMailDialog();
            return;
        }
        if (this.viewingMail) {
            this.content.innerHTML = `
            <div class="table-container" style="height: ${this.widget.height}px">
                ${this.viewingMail.html || this.viewingMail.preview || this.viewingMail.description}
            </div>
            <button class="close-mail-button">X</button>`;
            this.shadowRoot.querySelector('.close-mail-button').addEventListener('click', (e) => {
                this.viewingMail = null;
                this.render();
            });
            return;
        }
        const filteredMails = this.getFilteredMails();
        const start = (this.currentPage - 1) * this.itemsPerPage;
        const end = start + this.itemsPerPage;
        const paginatedMails = filteredMails.slice(start, end);
        const totalPages = Math.ceil(filteredMails.length / this.itemsPerPage);
        const unReadMails = this.mails.filter(mail => !mail.seen).length;
        const attachMails = this.mails.filter(mail => mail.attachments?.size > 0).length;

        this.content.innerHTML = `
      <div class="mail-widget">
        <div class="filter-controls">
          <button class="filter-btn ${this.currentFilter === 'all' ? 'active' : ''}" 
                  data-filter="all">All(${this.mails.length})</button>
          <button class="filter-btn ${this.currentFilter === 'unread' ? 'active' : ''}" 
                  data-filter="unread">Unread(${unReadMails})</button>
          <button class="filter-btn ${this.currentFilter === 'attachments' ? 'active' : ''}" 
                  data-filter="attachments">Attach(${attachMails})</button>
          <div class="search">
            <input value="${this.searchQuery}" class="search-input" placeholder="Search emails...">
            <div class="counter">${filteredMails.length}</div>
          </div>
          <button class="compose-button">Compose</button>
          <div class="pagination-controls">
            <button class="page-button prev-button" ${this.currentPage === 1 ? 'disabled' : ''}><</button>
            <span>Page ${this.currentPage} of ${totalPages}
              <select class="items-per-page-select">
                ${[5, 10, 15, 20, 50, 100, 1000].map(option =>
            `<option value="${option}" ${this.itemsPerPage === option ? 'selected' : ''}>${option}</option>`
        ).join('')}
              </select> 
            </span>
            <button class="page-button next-button" ${this.currentPage === totalPages ? 'disabled' : ''}>></button>
          </div>        
        </div>

        <div class="table-container" style="height: ${this.widget.height - 24}px">
          <table class="mail-table">
            <thead>
              <tr>
                <th class="sender-cell sort-header" data-sort="sender">
                  Sender
                  ${this.sortBy === 'sender' ?
            `<span class="sort-indicator">${this.sortDir === 'asc' ? '↑' : '↓'}</span>` : ''}
                </th>
                <th class="sort-header" data-sort="subject">
                  Subject
                  ${this.sortBy === 'subject' ?
            `<span class="sort-indicator">${this.sortDir === 'asc' ? '↑' : '↓'}</span>` : ''}
                </th>
                <th class="date-cell sort-header" data-sort="date">
                  Date
                  ${this.sortBy === 'date' ?
            `<span class="sort-indicator">${this.sortDir === 'asc' ? '↑' : '↓'}</span>` : ''}
                </th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              ${paginatedMails.map(mail => `
                <tr class="mail-row ${mail.seen ? '' : 'unread'}" data-id="${mail.id}">
                  <td>
                    ${mail.attachments?.size > 0 ? '<span class="attachment-icon">📎</span>' : ''}
                    ${mail.sender}
                  </td>
                  <td>
                    <div class="subject-line">${mail.subject}</div>
                    ${mail.preview || mail.description ? `<div class="preview-text">${mail.preview || mail.description}</div>` : ''}
                  </td>
                  <td>
                    ${this.formatDate(mail.receivedDate)}
                  </td>
                  <td>
                    <button class="delete-button" data-id="${mail.id}">×</button>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
      </div>
    `;

        const searchInput = this.shadowRoot.querySelector('.search-input');
        if (searchInput) {
            if (this.searchQuery) {
                searchInput.focus();
                searchInput.setSelectionRange(searchInput.value.length, searchInput.value.length);
            }
            searchInput.addEventListener('input', (e) => this.handleSearch(e));
        }

        this.shadowRoot.querySelector('.items-per-page-select').addEventListener('change', (e) => {
            this.itemsPerPage = parseInt(e.target.value, 10);
            this.render();
        });
        this.shadowRoot.querySelectorAll('.sort-header').forEach(header => {
            header.addEventListener('click', (e) => this.handleSort(e));
        });

        this.shadowRoot.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', (e) => this.handleFilter(e));
        });

        this.shadowRoot.querySelectorAll('.delete-button').forEach(btn => {
            btn.addEventListener('click', (e) => this.deleteMail(e));
        });

        this.shadowRoot.querySelectorAll('.mail-row').forEach(row => {
            row.addEventListener('click', (e) => this.viewMail(e));
        });

        this.shadowRoot.querySelector('.prev-button')?.addEventListener('click', () => this.prevPage());
        this.shadowRoot.querySelector('.next-button')?.addEventListener('click', () => this.nextPage());
        this.shadowRoot.querySelector('.compose-button').addEventListener('click', () => this.openComposeForm());
    }

    showSendMailDialog() {
        this.content.innerHTML = `
                <div class="compose-mail-form">
                    <h3>Compose Mail</h3>
                    <form id="composeForm" class="composeForm">
                        <input type="email" placeholder="To" id="toInput" required>
                        <input type="text" placeholder="Subject" id="subjectInput" required>
                        <div class="attachments">
                            <div class="attachment">
                                <input type="file" class="attachmentInput">
                                <button type="button" class="remove-button">❌</button>
                            </div>
                        </div>   
                        <textarea placeholder="Message" id="messageInput" required></textarea>
                        <button type="submit">Send</button>
                        <button type="button" class="cancel-button">Cancel</button>
                    </form>
                </div> 
            `;
        this.shadowRoot.querySelector('.cancel-button').addEventListener('click', () => this.closeComposeForm());
        this.shadowRoot.querySelector('#composeForm').addEventListener('submit', (e) => this.sendMail(e));
        const attachmentsDiv = this.shadowRoot.querySelector('.attachments');
        this.shadowRoot.querySelector('.attachments').addEventListener("change", function (event) {
            if (event.target.classList.contains("attachmentInput")) {
                if (event.target.files.length > 0) {
                    const attachmentDiv = document.createElement("div");
                    attachmentDiv.classList.add("attachment");

                    const newInput = document.createElement("input");
                    newInput.type = "file";
                    newInput.classList.add("attachmentInput");

                    const removeButton = document.createElement("button");
                    removeButton.type = "button";
                    removeButton.classList.add("remove-button");
                    removeButton.classList.add("visible");
                    removeButton.textContent = "❌";
                    removeButton.addEventListener("click", function () {
                        attachmentDiv.remove();
                    });

                    attachmentDiv.appendChild(newInput);
                    attachmentDiv.appendChild(removeButton);
                    attachmentsDiv.appendChild(attachmentDiv);
                }
            }
        });

        // Add the event listener for clicking on the remove button
        this.shadowRoot.querySelector('.attachments').addEventListener("click", function (event) {
            if (event.target.classList.contains("remove-button")) {
                let fileInputs = this.querySelectorAll('input[type="file"]');
                // If there is only one file input, clear it instead of removing it
                if (fileInputs.length === 1) {
                    fileInputs[0].value = ""; // Clear the first input field
                    event.target.style.display = 'none'; // Hide the remove button if the input is cleared
                } else {
                    event.target.parentElement.remove(); // Otherwise, remove the file input element
                }
            }
        });

        // Monitor the file input change to show/hide the remove button
        this.shadowRoot.querySelector('.attachments').addEventListener("change", function () {
            let fileInputs = this.querySelectorAll('input[type="file"]');
            let firstFileInput = fileInputs[0];

            if (firstFileInput && firstFileInput.value) {
                // Show remove button if the first input is filled
                firstFileInput.nextElementSibling.style.display = 'inline-block';
            } else {
                // Hide remove button if the first input is empty
                firstFileInput.nextElementSibling.style.display = 'none';
            }
        });
    }

    closeComposeForm() {
        this.isComposeOpen = false;
        this.render();
    }

    openComposeForm() {
        this.isComposeOpen = true;
        this.render();
    }

    sendMail(event) {
        event.preventDefault();
        const to = this.shadowRoot.querySelector('#toInput').value;
        const subject = this.shadowRoot.querySelector('#subjectInput').value;
        const message = this.shadowRoot.querySelector('#messageInput').value;
        const formData = new FormData(event.target);
        const attachments = [...formData.getAll("attachmentInput")];

        this.widget.sendMail(to, subject, message, attachments);
    }

    deleteMail(event) {
        const mailId = event.target.getAttribute('data-id');
        this.widget.deleteMail(mailId);
    }

    formatDate(date) {
        const options = { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' };
        return new Date(date).toLocaleDateString(undefined, options);
    }

    getFilteredMails() {
        return this.mails.filter(mail => {
            if (this.currentFilter === 'unread' && mail.seen) return false;
            if (this.currentFilter === 'attachments' && !mail.attachments?.size) return false;
            return true;
        }).filter(mail => {
            return !this.searchQuery || mail.sender.includes(this.searchQuery) || mail.subject.includes(this.searchQuery);
        });
    }

    prevPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.render();
        }
    }

    nextPage() {
        const totalPages = Math.ceil(this.getFilteredMails().length / this.itemsPerPage);
        if (this.currentPage < totalPages) {
            this.currentPage++;
            this.render();
        }
    }

    handleSearch(event) {
        this.searchQuery = event.target.value;
        this.render();
    }

    handleSort(event) {
        const sortBy = event.target.getAttribute('data-sort');
        const sortDir = this.sortBy === sortBy && this.sortDir === 'asc' ? 'desc' : 'asc';
        this.sortBy = sortBy;
        this.sortDir = sortDir;
        this.render();
    }

    handleFilter(event) {
        this.currentFilter = event.target.getAttribute('data-filter');
        this.render();
    }
}
